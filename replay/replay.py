#!/usr/bin/env python3
"""Replay real public records into the RouteIQ ingest API.

Two sources, one per tenant:
  cfpb    CFPB Consumer Complaint Database (consumerfinance.gov search API)
  nyc311  NYC 311 Service Requests (Socrata dataset erm2-nwe9)

Records are sorted by their original timestamp and sent with the original gaps between them divided by --speed,
so --speed 1 is real time, 10 is ten times faster, and so on. The idempotency key is derived from the source
record id, so replaying the same file twice creates no duplicates.

Standard library only.

  python3 replay.py fetch  --source nyc311 --limit 5000
  python3 replay.py replay --source nyc311 --speed 100
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

DATA_DIR = Path(__file__).resolve().parent / "data"

CFPB_URL = "https://www.consumerfinance.gov/data-research/consumer-complaints/search/api/v1/"
NYC311_URL = "https://data.cityofnewyork.us/resource/erm2-nwe9.json"
USER_AGENT = "routeiq-replay/0.1"


# ---------------------------------------------------------------- fetching

def http_get_json(url: str, timeout: float = 60) -> object:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)


def fetch_cfpb(limit: int) -> list[dict]:
    query = urllib.parse.urlencode({"size": limit, "sort": "created_date_desc", "no_aggs": "true"})
    body = http_get_json(f"{CFPB_URL}?{query}")
    return [hit["_source"] for hit in body["hits"]["hits"]]


def fetch_nyc311(limit: int) -> list[dict]:
    query = urllib.parse.urlencode({
        "$limit": limit,
        "$order": "created_date DESC",
        "$select": "unique_key,created_date,agency,agency_name,complaint_type,descriptor,borough,incident_zip,city",
    })
    return http_get_json(f"{NYC311_URL}?{query}")


FETCHERS = {"cfpb": fetch_cfpb, "nyc311": fetch_nyc311}


def data_file(source: str) -> Path:
    return DATA_DIR / f"{source}.jsonl"


def cmd_fetch(args: argparse.Namespace) -> None:
    records = FETCHERS[args.source](args.limit)
    DATA_DIR.mkdir(exist_ok=True)
    path = data_file(args.source)
    with path.open("w") as out:
        for record in records:
            out.write(json.dumps(record) + "\n")
    print(f"saved {len(records)} {args.source} records to {path}")


# ---------------------------------------------------------------- mapping

def parse_time(value: str) -> datetime:
    """Both APIs use ISO-8601; Socrata omits the zone (NYC local time is close enough for replay spacing)."""
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def compact(values: dict) -> dict:
    return {k: str(v) for k, v in values.items() if v not in (None, "")}


def to_issue(source: str, record: dict) -> dict:
    if source == "cfpb":
        title = record.get("issue") or record.get("product") or "Consumer complaint"
        if record.get("sub_issue"):
            title = f"{title}: {record['sub_issue']}"
        return {
            "externalId": str(record["complaint_id"]),
            "title": title[:500],
            "description": (record.get("complaint_what_happened") or "")[:10000] or None,
            "category": record.get("product"),
            "sourceCreatedAt": parse_time(record["date_received"]).isoformat(),
            "attributes": compact({
                "subProduct": record.get("sub_product"),
                "company": record.get("company"),
                "state": record.get("state"),
                "submittedVia": record.get("submitted_via"),
            }),
        }
    if source == "nyc311":
        title = record.get("descriptor") or record.get("complaint_type") or "311 request"
        return {
            "externalId": str(record["unique_key"]),
            "title": title[:500],
            "category": record.get("complaint_type"),
            "sourceCreatedAt": parse_time(record["created_date"]).isoformat(),
            "attributes": compact({
                "agency": record.get("agency"),
                "borough": record.get("borough"),
                "zip": record.get("incident_zip"),
                "city": record.get("city"),
            }),
        }
    raise ValueError(f"unknown source {source}")


def schedule(issues: list[dict], speed: float, max_gap: float | None = None) -> list[tuple[float, dict]]:
    """Returns (offset_seconds, issue) pairs in send order. Gaps are divided by speed and optionally capped."""
    ordered = sorted(issues, key=lambda i: i["sourceCreatedAt"])
    plan: list[tuple[float, dict]] = []
    offset = 0.0
    previous: datetime | None = None
    for issue in ordered:
        at = datetime.fromisoformat(issue["sourceCreatedAt"])
        if previous is not None:
            gap = max((at - previous).total_seconds(), 0) / speed
            offset += min(gap, max_gap) if max_gap is not None else gap
        plan.append((offset, issue))
        previous = at
    return plan


# ---------------------------------------------------------------- sending

@dataclass
class Stats:
    lock: threading.Lock = field(default_factory=threading.Lock)
    created: int = 0
    replayed: int = 0
    errors: int = 0
    latencies_ms: list[float] = field(default_factory=list)
    max_lag_ms: float = 0.0

    def record(self, status: int | None, latency_ms: float, lag_ms: float) -> None:
        with self.lock:
            if status == 201:
                self.created += 1
            elif status == 200:
                self.replayed += 1
            else:
                self.errors += 1
            self.latencies_ms.append(latency_ms)
            self.max_lag_ms = max(self.max_lag_ms, lag_ms)

    def summary(self) -> str:
        lat = sorted(self.latencies_ms) or [0.0]
        p = lambda q: lat[min(len(lat) - 1, int(q * len(lat)))]
        return (f"created={self.created} replayed={self.replayed} errors={self.errors} "
                f"http_p50={p(0.5):.1f}ms http_p95={p(0.95):.1f}ms http_p99={p(0.99):.1f}ms "
                f"mean={statistics.fmean(lat):.1f}ms max_schedule_lag={self.max_lag_ms:.0f}ms")


def post_issue(api: str, tenant: str, issue: dict, timeout: float) -> int | None:
    body = json.dumps(issue).encode()
    request = urllib.request.Request(
        f"{api}/api/tenants/{tenant}/issues", data=body, method="POST",
        headers={"Content-Type": "application/json", "Idempotency-Key": f"{tenant}:{issue['externalId']}"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status
    except urllib.error.HTTPError as e:
        print(f"HTTP {e.code} for {issue['externalId']}: {e.read()[:200]!r}", file=sys.stderr)
        return e.code
    except (urllib.error.URLError, TimeoutError) as e:
        print(f"error for {issue['externalId']}: {e}", file=sys.stderr)
        return None


def cmd_replay(args: argparse.Namespace) -> None:
    path = data_file(args.source)
    if not path.exists():
        sys.exit(f"{path} not found; run: python3 replay.py fetch --source {args.source}")
    records = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
    if args.limit:
        records = records[: args.limit]
    plan = schedule([to_issue(args.source, r) for r in records], args.speed, args.max_gap)
    duration = plan[-1][0] if plan else 0
    print(f"replaying {len(plan)} {args.source} records at {args.speed}x over ~{duration:.0f}s to {args.api}")

    stats = Stats()
    start = time.monotonic()

    def send(offset: float, issue: dict) -> None:
        lag_ms = (time.monotonic() - start - offset) * 1000
        t0 = time.monotonic()
        status = post_issue(args.api, args.source, issue, args.timeout)
        stats.record(status, (time.monotonic() - t0) * 1000, lag_ms)

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        last_report = start
        for offset, issue in plan:
            delay = start + offset - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            pool.submit(send, offset, issue)
            if time.monotonic() - last_report > 10:
                print(f"  t={time.monotonic() - start:.0f}s {stats.summary()}", flush=True)
                last_report = time.monotonic()

    elapsed = time.monotonic() - start
    print(f"done in {elapsed:.1f}s ({len(plan) / max(elapsed, 1e-9):.1f} req/s): {stats.summary()}")


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    fetch = sub.add_parser("fetch", help="download recent records to replay/data/<source>.jsonl")
    fetch.add_argument("--source", choices=FETCHERS, required=True)
    fetch.add_argument("--limit", type=int, default=2000)
    fetch.set_defaults(func=cmd_fetch)

    replay = sub.add_parser("replay", help="stream saved records into the API")
    replay.add_argument("--source", choices=FETCHERS, required=True)
    replay.add_argument("--speed", type=float, default=10.0, help="1 = real time, 10, 100, ...")
    replay.add_argument("--max-gap", type=float, default=None, help="cap any single gap at this many seconds")
    replay.add_argument("--limit", type=int, default=None)
    replay.add_argument("--api", default="http://localhost:8080")
    replay.add_argument("--workers", type=int, default=32)
    replay.add_argument("--timeout", type=float, default=10.0)
    replay.set_defaults(func=cmd_replay)

    args = parser.parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main()

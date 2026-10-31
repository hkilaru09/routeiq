-- End-to-end pipeline latency (ingest commit -> routed) for issues created by a load test run.
SELECT count(*)                                                                                   AS issues,
       count(*) FILTER (WHERE routed_at IS NULL)                                                  AS not_routed,
       round((percentile_cont(0.50) WITHIN GROUP (ORDER BY extract(epoch FROM routed_at - created_at)) * 1000)::numeric, 1) AS p50_ms,
       round((percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM routed_at - created_at)) * 1000)::numeric, 1) AS p95_ms,
       round((percentile_cont(0.99) WITHIN GROUP (ORDER BY extract(epoch FROM routed_at - created_at)) * 1000)::numeric, 1) AS p99_ms
FROM issues
WHERE external_id LIKE 'k6-' || :'run' || '-%';

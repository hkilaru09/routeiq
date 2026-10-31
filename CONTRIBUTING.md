# Contributing to RouteIQ

Thanks for taking a look. Bug reports, questions, docs fixes and code are all welcome, and you don't need to know
Kafka or Spring well to help: several open tasks are dashboard-only or Python-only.

## Ways to help

- **Ask a question or report a bug.** Open an issue with the template. If something in the README didn't work for you,
  that's a bug.
- **Pick up an issue.** Issues labelled [`good first issue`](https://github.com/hkilaru09/routeiq/labels/good%20first%20issue)
  are scoped to one area and list the files to touch. Comment on the issue so nobody else starts the same thing.
- **Propose something bigger.** For a new feature or a design change, open an issue first so we can agree on the shape
  before you write code. The [roadmap](README.md#roadmap) lists what's already planned.

## Project layout

| Path | What | Stack |
|---|---|---|
| `service/` | API, outbox relay, router, notifier, SLA scheduler, board WebSocket (one app, roles switched by env vars) | Java 21, Spring Boot 3.5, Spring Kafka, JDBC, Flyway |
| `service/src/main/resources/db/migration/` | Schema and seed data | PostgreSQL 16 |
| `dashboard/` | Live board and rule editor | React 19, TypeScript, Vite, Vitest |
| `replay/` | Fetches CFPB / NYC 311 records and replays them at N× real time | Python 3.11+, standard library only |
| `loadtest/` | k6 scripts and the latency query used for the README benchmarks | k6, SQL |
| `deploy/` | Helm chart, Prometheus and Grafana config | Helm, YAML |

## Development setup

You need Docker. Depending on what you change: JDK 21, Node 22, Python 3.11+.

Start only the infrastructure, then run the part you're working on from source:

```bash
docker compose up -d postgres redis kafka webhook-sink
```

**Service**

```bash
cd service
KAFKA_BOOTSTRAP_SERVERS=localhost:9094 ./mvnw spring-boot:run
```

Postgres (`localhost:5432`) and Redis (`localhost:6379`) match the defaults in `application.yml`. Kafka's host listener
is `9094`. Email is off unless `SMTP_HOST` is set. Flyway applies the migrations on startup.

**Dashboard**

```bash
cd dashboard
npm ci
npm run dev        # http://localhost:5173, proxies /api and /ws to localhost:8080
```

Set `ROUTEIQ_API` to point the proxy somewhere else.

**Replay producer**

```bash
cd replay
python3 replay.py fetch  --source nyc311 --limit 500
python3 replay.py replay --source nyc311 --speed 100
```

## Tests

Run the suite for the area you changed before opening a pull request. CI runs all of them.

```bash
cd service && ./mvnw verify                   # unit + Testcontainers; needs Docker running
cd dashboard && npm test && npm run build
cd replay && python3 -m unittest -v test_replay
helm lint deploy/helm/routeiq                 # if you touched the chart
```

`PipelineIntegrationTest` starts real Postgres, Kafka and Redis containers, so the first run pulls images and takes a
few minutes.

## Making a change

1. Fork the repo and create a branch from `main`.
2. Keep the change focused: one fix or feature per pull request.
3. Add or update tests. Behaviour that crosses Kafka or the database belongs in `PipelineIntegrationTest`; pure
   logic (rules, hashing, board state) gets a unit test.
4. Schema changes go in a **new** Flyway migration (`V3__...sql`, and so on). Never edit an existing migration.
5. Update the README if you change the API, configuration, or how something is run.
6. Open a pull request and fill in the template. Draft pull requests are fine if you want early feedback.

### Conventions

- **Java:** follow the style of the surrounding code. `JdbcClient` with plain SQL, no JPA. Consumers must stay
  idempotent: a message can be delivered more than once.
- **Events:** state changes write to `outbox` in the same transaction; nothing publishes to Kafka directly from a
  request thread. `issue_events` is append-only.
- **TypeScript:** strict mode; keep state transitions in `boardState.ts` so they can be unit tested.
- **Python:** standard library only in `replay/`.
- **Commits:** short imperative subject line ("Add NOT_CONTAINS rule operator"), with a body explaining why when it
  isn't obvious.

### Benchmarks

If you change something on the hot path and post numbers, say what you ran and on what hardware, and use the scripts in
`loadtest/`. Targets or estimates are welcome in discussion but don't go in the README as results.

## Code of conduct

This project follows the [Code of Conduct](CODE_OF_CONDUCT.md). Be kind and assume good intent.

## License

By contributing, you agree that your contributions are licensed under the [MIT License](LICENSE).

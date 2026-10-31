## What and why

<!-- What does this change, and what problem does it solve? Link the issue: "Closes #123". -->

## How it was tested

<!-- Which suites you ran, and anything you checked by hand (requests, dashboard, compose). -->

- [ ] `./mvnw verify` (service)
- [ ] `npm test && npm run build` (dashboard)
- [ ] `python3 -m unittest -v test_replay` (replay)

## Checklist

- [ ] Tests added or updated for the change
- [ ] Schema changes are in a new Flyway migration, not an edit to an existing one
- [ ] README or CONTRIBUTING updated if the API, configuration or setup changed

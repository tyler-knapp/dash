# CLAUDE.md

Personal finance app for one user (Tyler). Read `SPEC.md` before any work; it is the source of truth for scope, data model, API and build order.

## Repo layout
- `backend/` Spring Boot 3, Java 21, Gradle. Postgres via Flyway.
- `frontend/` React + TypeScript, Vite.
- `reference/prototype.html` — the approved UI. Match its tokens, fonts, layout and wording. Port its period/budget logic; don't reinvent it.
- `reference/sample-data/` — ten months of fake transactions, budget history and balances in the prototype's format. Use for fixtures and seed data in dev.

## Rules
- Work phase by phase (SPEC §10). Finish a phase's "Done when" checks before starting the next. Record what you verified in `PROGRESS.md`.
- Confirm status, don't assume it: say what you ran and what passed. If something couldn't be tested, say so.
- App sign convention: negative = money out. Plaid's sign is flipped once at ingest.
- Secrets live only in env vars. Never log tokens, keys or account numbers. Never commit `.env`.
- Plaid: Sandbox only until Phase 6. Ask before linking any production institution; Items are limited and slots don't come back.
- Verify library versions and API shapes against current docs before adding dependencies. Don't guess.
- Keep commits small and scoped to one change.

## Commands
- Start everything: `docker compose up --build` (or `make up`), then http://localhost:8080. Backend direct: http://localhost:8081/api/health
- Dev sample data (`reference/sample-data`) loads by default in compose; start empty with `SAMPLE_DATA=false`. It's deleted automatically on the first real Plaid link.
- Smoke-check a running stack: `make smoke`
- All tests (what CI runs): `make test` (needs Docker for Testcontainers)
- Backend tests: `cd backend && ./gradlew test` (Plaid is faked; real-Sandbox test is skipped)
- Real Plaid Sandbox test: `cd backend && PLAID_SANDBOX_IT=true PLAID_ENV=sandbox PLAID_CLIENT_ID=… PLAID_SECRET=… ./gradlew test --tests '*PlaidSandboxIT'`
- Backend dev (db in docker, app on :8081): `make dev-backend`
- Frontend dev server: `cd frontend && npm run dev` (proxies /api to :8081)
- Frontend tests: `cd frontend && npm test` (also `npm run lint`, `npm run typecheck`)
- Re-record the prototype's budget view as test fixtures (only if the prototype or sample data changes): `cd scripts/prototype-oracle && npm install && npm run capture`

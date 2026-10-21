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
Fill these in during Phase 0:
- Start everything: `docker compose up`
- Backend tests: 
- Frontend dev server: 
- Frontend tests: 

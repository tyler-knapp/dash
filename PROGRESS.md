# Progress

One entry per phase (SPEC §10): what was built, what was verified, and how.

## Phase 0 — Scaffold ✅ (2026-10-21)

**Built**
- Monorepo: `backend/` (Spring Boot 3.5.16, Java 21, Gradle 8.14.3 wrapper), `frontend/` (React 19 + TypeScript, Vite 8), `docker-compose.yml` (Postgres 16 + backend + frontend), `Makefile`.
- Flyway `V1__schema.sql`: every table in SPEC §4 (`item`, `account`, `transaction`, `category`, `subcategory`, `budget_version`, `balance_snapshot`, `category_rule`). Money is `NUMERIC(14,2)`. Constraints: category/subcategory pairs must exist in the taxonomy (composite FK), manual accounts can't carry Plaid ids, `budget_version.effective_from` must be `YYYY-MM`, enum-like columns have CHECKs.
- Flyway `V2__seed_taxonomy.sql`: the 9 categories and 32 subcategories, same names and order as the prototype.
- Env config bound to `AppProperties` (`PLAID_CLIENT_ID`, `PLAID_SECRET`, `PLAID_ENV` = sandbox|production, `TOKEN_ENC_KEY`, `ANTHROPIC_API_KEY`, `APP_PASSWORD_HASH`). Startup fails on an unknown `PLAID_ENV`. `toString()` never prints secret values. `.env.example` provided; `.env` is gitignored.
- `GET /api/health` → `{status, db, plaidEnv, timeZone, config:{plaid, tokenEncKey, anthropic, auth}}`. Config fields are booleans only. Returns 503 if the DB is down.
- Frontend shell: prototype stylesheet ported verbatim (`src/styles/app.css`, tokens + both dark-mode blocks), the three Google Fonts, a typed fetch client, and a status page with loading / ok / error states. nginx serves the build and proxies `/api` to the backend (same origin, ready for the session cookie in Phase 6).
- `make test` = backend tests + frontend lint, typecheck, tests, production build. GitHub Actions runs `make test` on push and PR.

**Verified**
- `./gradlew cleanTest test`: 10/10 passed, against a real Postgres 16 via Testcontainers:
  - health endpoint returns 200, `db: ok`, `plaidEnv: sandbox`, config values are booleans only;
  - all 8 tables exist after migration; taxonomy matches the prototype's `CATS` exactly, in order; only Income is `income`, only Transfer is `transfer`;
  - DB rejects `Food › Gas`, a category without a subcategory, a manual account with a Plaid id, and budget month `2026-13`;
  - config binding rejects `PLAID_ENV=development`, accepts `production`, and secrets don't appear in `toString()`.
- Frontend: `npm run lint`, `npm run typecheck`, `npm test` (2/2: healthy and server-down states), `npm run build` all clean.
- `make test` ran end to end and passed.
- `docker compose up --build`: all three containers healthy; Flyway applied V1 and V2 on a fresh volume. `make smoke` passed: frontend HTML served on :8080, `/api/health` OK through nginx (:8080) and directly (:8081). Headless Chromium screenshots at 980px and 400px show the prototype fonts and colors and the status banner.

**Notes**
- The dev sandbox this was built in intercepts HTTPS, so image builds there needed an extra CA. Both Dockerfiles accept an optional build secret `extra_ca` (a PEM bundle) for that case; on a normal machine nothing is needed and the step does nothing.
- No budget versions are seeded: the prototype's amounts are placeholders (SPEC §12 Q4). Sample data loading for dev comes with Phases 2–3.
- Auth is not wired yet (Phase 6); `config.auth` in the health response just reports whether `APP_PASSWORD_HASH` is set.

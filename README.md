# Personal Finance

Single-user personal finance app: Plaid transactions and balances, auto-categorization, monthly budgets, net worth. See `SPEC.md` for scope and `PROGRESS.md` for status.

## Run it

```sh
cp .env.example .env      # fill in keys as phases need them; empty is fine for Phase 0
docker compose up --build
```

- App: http://localhost:8080
- API health: http://localhost:8081/api/health

To use it from your phone and other devices (private, via Tailscale), and for the long-term home-server setup, see [`docs/hosting.md`](docs/hosting.md).

## Develop

```sh
make test           # everything CI runs (Docker required for backend tests)
make dev-backend    # Postgres in docker, backend on :8081
make dev-frontend   # Vite dev server, proxies /api to :8081
```

Layout: `backend/` Spring Boot 3 + Flyway, `frontend/` React + TypeScript (Vite), `reference/` the approved prototype and sample data.

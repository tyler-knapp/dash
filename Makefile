# CI-style entry points. `make test` is what CI runs.
.PHONY: test test-backend test-frontend up down logs dev-backend dev-frontend smoke

test: test-backend test-frontend

# Needs Docker running (Testcontainers starts a Postgres 16).
test-backend:
	cd backend && ./gradlew --no-daemon test

test-frontend:
	cd frontend && npm ci && npm run lint && npm run typecheck && npm test && npm run build

up:
	docker compose up --build -d
	@echo "Frontend: http://localhost:8080   Backend: http://localhost:8081/api/health"

down:
	docker compose down

logs:
	docker compose logs -f

# Checks the running stack: frontend served, /api/health OK through nginx and directly.
smoke:
	curl -fsS http://localhost:8080/ | grep -q '<div id="root">'
	curl -fsS http://localhost:8080/api/health | grep -q '"status":"ok"'
	curl -fsS http://localhost:8081/api/health | grep -q '"db":"ok"'
	@echo "smoke OK"

# Local dev without containers for the app (db still in docker).
dev-backend:
	docker compose up -d db
	cd backend && PORT=8081 SAMPLE_DATA=$${SAMPLE_DATA:-true} ./gradlew bootRun

dev-frontend:
	cd frontend && npm run dev

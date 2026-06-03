# EasyQL

A text-to-SQL agent built on Spring AI. A worker LLM generates SQL grounded in retrieved schema; a verifier agent runs a deterministic-checks-then-LLM pipeline that catches bad queries before they execute, with a repair loop on failure.

**Project thesis:** the verifier loop is the centerpiece. The BIRD benchmark number is the proof, not the headline.

Full design: [`docs/superpowers/specs/2026-06-03-easyql-design.md`](docs/superpowers/specs/2026-06-03-easyql-design.md).

## Status

This is an in-progress portfolio project. Built in incremental plans tracked in [`docs/superpowers/plans/`](docs/superpowers/plans/).

**Plan 1 (this commit set) ships:**
- Spring Boot 3 application skeleton (Java 21, Maven)
- Three Postgres instances via Docker Compose (`warehouse_db`, `vectors_db`, `traces_db`)
- Flyway wired to `warehouse_db` with a baseline migration
- `GET /health` endpoint
- Testcontainers-backed integration test proving the warehouse migration pipeline
- Maven Failsafe plugin so integration tests run via `mvn verify`

**Not yet built (later plans):**
- Custom 80-table B2B SaaS warehouse schema
- Synthetic data via DataFaker
- Embedding service via the CastAI OpenAI gateway
- pgvector retrieval (hierarchical: tables → columns, plus exemplar questions)
- Worker LLM call
- Verifier deterministic pipeline (parse / schema-ground / dry-execute / plan-heuristics / live-execute)
- LLM verifier with repair loop and abstention
- Caching (exact + semantic)
- Observability via self-hosted Langfuse + Grafana
- BIRD eval harness (Python wrapper)
- Smoke set + adversarial set + sentinel canaries

## Prerequisites

- Java 21 (`java -version` should show 21.x)
- Maven 3.9+ (`mvn -version`)
- Docker + Docker Compose (`docker compose version`)

## Build

```bash
mvn clean compile
```

## Run the full stack locally

Start the three Postgres instances:

```bash
docker compose -f infra/docker-compose.yml up -d
```

Wait ~15 seconds for healthchecks, then verify:

```bash
docker compose -f infra/docker-compose.yml ps
```

All three services should show `(healthy)`.

Start the application:

```bash
mvn spring-boot:run
```

Flyway will apply `V1__baseline.sql` to `warehouse_db` on first start. Confirm:

```bash
curl -s http://localhost:8080/health | jq
# → {"status":"UP","app":"easy-ql"}
```

## Test

```bash
mvn verify
```

Surefire runs unit tests (`*Test.java`); Failsafe runs integration tests (`*IT.java`). Testcontainers will pull `postgres:16` the first time `mvn verify` is invoked.

## Stop the stack

```bash
docker compose -f infra/docker-compose.yml down
```

To also drop the data volumes:

```bash
docker compose -f infra/docker-compose.yml down -v
```

## Project layout (current; expands with each plan)

```
src/main/java/com/soumyajit/easyql/
  EasyqlApplication.java        Spring Boot entrypoint
  api/                          REST endpoints  (Plan 1: HealthController)
src/main/resources/
  application.yml               app config (datasources, flyway, server)
  db/warehouse/                 Flyway migrations for warehouse_db
infra/
  docker-compose.yml            Local Docker stack
docs/superpowers/
  specs/                        Design specs
  plans/                        Implementation plans (one per delivery slice)
```

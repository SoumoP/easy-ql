# EasyQL

A text-to-SQL agent built on Spring AI. A worker LLM generates SQL grounded in retrieved schema; a verifier agent runs a deterministic-checks-then-LLM pipeline that catches bad queries before they execute, with a repair loop on failure.

**Project thesis:** the verifier loop is the centerpiece. The BIRD benchmark number is the proof, not the headline.

Full design: [`docs/superpowers/specs/2026-06-03-easyql-design.md`](docs/superpowers/specs/2026-06-03-easyql-design.md).

## Status

This is an in-progress portfolio project. Built in incremental plans tracked in [`docs/superpowers/plans/`](docs/superpowers/plans/).

**Plan 1 ships:**
- Spring Boot 3 application skeleton (Java 21, Maven)
- Three Postgres instances via Docker Compose (`warehouse_db`, `vectors_db`, `traces_db`)
- Flyway wired to `warehouse_db` with a baseline migration
- `GET /health` endpoint
- Testcontainers-backed integration test proving the warehouse migration pipeline
- Maven Failsafe plugin so integration tests run via `mvn verify`

**Plan 2 ships:**
- 20-table B2B SaaS warehouse schema (`V2__warehouse_schema.sql`) — trimmed from the spec's ~80-table target to keep setup focused on retrieval pressure rather than bulk schema repetition
- Synthetic data seeder using DataFaker (`SchemaSeeder`), profile-gated to `seed` so it cannot run by accident, idempotent via TRUNCATE+reseed with a fixed Faker seed
- Schema integration test asserting all 20 tables exist and six deliberate retrieval-pressure warts are encoded
- Seeder integration test asserting row counts, FK integrity, multi-tenant consistency, and idempotency

**Deliberate retrieval-pressure warts in the warehouse** (these are the point — they force retrieval to do real work, not the table count):

1. **`users` vs `deprecated_v1_users`** — same domain, divergent column names (`email` vs `email_addr`, `name` vs `full_name`, `created_at` vs `joined_dt`). A keyword retriever picking the wrong one returns wrong data.
2. **`users.status` (text enum) + `users.is_active` (boolean)** — redundant fields that look interchangeable but drift in real schemas; the worker has to know which the question really meant.
3. **Three raw/rollup pairs** where the same business question has two valid sources:
   - `invoices` + `payments` ↔ `monthly_recurring_revenue`
   - `usage_events` ↔ `feature_adoption_rollups`
   - `churn_signals` ↔ `churn_cohort_facts`
4. **`role_assignments` forces a 3-way join** (`users` + `role_assignments` + `roles`) for "who has admin permissions"-style questions.
5. **`org_id` cascades into every tenant table** — the worker that forgets multi-tenant scoping returns cross-tenant data.
6. **`stripe_customers.stripe_customer_id`** is a different ID space than `users.id` — cross-system joins require knowing the bridge table exists.

**Not yet built (later plans):**
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

Flyway will apply `V1__baseline.sql` and `V2__warehouse_schema.sql` to `warehouse_db` on first start. Confirm:

```bash
curl -s http://localhost:8090/health | jq
# → {"status":"UP","app":"easy-ql"}
```

## Seed the warehouse with synthetic data

The `seed` Spring profile activates the `SchemaSeeder`, which TRUNCATEs all 20 warehouse tables and repopulates them with reproducible synthetic data from DataFaker. About 40,000 rows in ~15 seconds.

```bash
SPRING_PROFILES_ACTIVE=seed mvn spring-boot:run
```

The seeder runs once on startup, logs per-table row counts, then the app continues running normally. Same input seed → same output, so CI runs are deterministic.

To verify the data landed:

```bash
docker exec easyql-warehouse-db psql -U easyql -d warehouse_db \
  -c "select 'orgs' as t, count(*) from orgs union all
      select 'users', count(*) from users union all
      select 'invoices', count(*) from invoices union all
      select 'usage_events', count(*) from usage_events
      order by t;"
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
  seed/                         Synthetic data seeder  (Plan 2: SchemaSeeder)
src/main/resources/
  application.yml               app config (datasources, flyway, server)
  db/warehouse/                 Flyway migrations for warehouse_db
infra/
  docker-compose.yml            Local Docker stack
docs/superpowers/
  specs/                        Design specs
  plans/                        Implementation plans (one per delivery slice)
```

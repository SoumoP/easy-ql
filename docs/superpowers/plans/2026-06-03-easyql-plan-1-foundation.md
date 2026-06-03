# EasyQL Plan 1: Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the EasyQL project skeleton — Spring Boot app with health endpoint, three Postgres instances via Docker, Flyway wired to the warehouse database, and a README explaining how to build and run.

**Architecture:** Single Spring Boot 3 application (modular monolith per spec §3.1). Datasources for `warehouse_db` declared in Spring config; `vectors_db` and `traces_db` declared in `docker-compose.yml` but not wired into Spring until later plans. Flyway manages warehouse migrations; the only migration in this plan is a baseline that records project metadata, proving end-to-end wiring.

**Tech Stack:**
- Java 21, Spring Boot 3.3.5, Maven
- Postgres 16 (three instances via Docker Compose; `vectors_db` uses `pgvector/pgvector:pg16` image so we don't have to swap images later)
- Flyway 10 (warehouse migrations only in this plan)
- HikariCP (default Spring Boot datasource pool)
- JUnit 5, Testcontainers 1.20 for integration tests

**Spec reference:** `docs/superpowers/specs/2026-06-03-easyql-design.md`. This plan implements only what's needed to satisfy spec §3.3 (persistence — partial), §3.4 (`GET /health`), and §10 (technical stack — partial).

---

## File Structure

Files created or modified in this plan:

```
easy-ql/
  pom.xml                                                                    (create)
  .gitignore                                                                 (create)
  README.md                                                                  (create)
  infra/docker-compose.yml                                                   (create)
  src/main/java/com/soumyajit/easyql/EasyqlApplication.java                  (create)
  src/main/java/com/soumyajit/easyql/api/HealthController.java               (create)
  src/main/resources/application.yml                                         (create)
  src/main/resources/db/warehouse/V1__baseline.sql                           (create)
  src/test/java/com/soumyajit/easyql/api/HealthControllerTest.java           (create)
  src/test/java/com/soumyajit/easyql/WarehouseConnectionIT.java              (create)
  src/test/resources/application-test.yml                                    (create)
```

Module boundaries from the spec are reflected in package layout from day 1. `api/`, `config/` appear in Plan 1; `retrieval/`, `schema/`, `worker/`, `verifier/`, `executor/`, `cache/`, `observability/`, `eval/` appear in later plans.

---

## Task 1: Maven scaffold (pom.xml + main class + package layout)

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/soumyajit/easyql/EasyqlApplication.java`
- Create: `src/main/resources/application.yml` (placeholder; real content in Task 3)
- Create: `.gitignore`

This task does not have a failing-test step because there is nothing to test yet. The verification is that `mvn compile` succeeds.

- [ ] **Step 1.1: Create `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.5</version>
        <relativePath/>
    </parent>

    <groupId>com.soumyajit</groupId>
    <artifactId>easy-ql</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>easy-ql</name>
    <description>Text-to-SQL agent with a verifier loop</description>

    <properties>
        <java.version>21</java.version>
        <testcontainers.version>1.20.4</testcontainers.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${testcontainers.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <version>${testcontainers.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 1.2: Create `src/main/java/com/soumyajit/easyql/EasyqlApplication.java`**

```java
package com.soumyajit.easyql;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class EasyqlApplication {
    public static void main(String[] args) {
        SpringApplication.run(EasyqlApplication.class, args);
    }
}
```

- [ ] **Step 1.3: Create placeholder `src/main/resources/application.yml`**

```yaml
spring:
  application:
    name: easy-ql
```

Real config arrives in Task 3.

- [ ] **Step 1.4: Create `.gitignore`**

```gitignore
# Maven
target/
.mvn/wrapper/maven-wrapper.jar
.mvnw

# IDEs
.idea/
*.iml
.vscode/
.project
.classpath
.settings/

# OS
.DS_Store

# Logs / runtime
*.log
logs/
```

- [ ] **Step 1.5: Verify build**

Run: `mvn compile -q`
Expected: exit code 0, no errors.

- [ ] **Step 1.6: Commit**

```bash
git add pom.xml src/main/java/com/soumyajit/easyql/EasyqlApplication.java src/main/resources/application.yml .gitignore
git commit -m "chore: bootstrap Maven project with Spring Boot 3.3 and Java 21"
```

---

## Task 2: `infra/docker-compose.yml` — three Postgres instances

**Files:**
- Create: `infra/docker-compose.yml`

- [ ] **Step 2.1: Create `infra/docker-compose.yml`**

```yaml
services:
  warehouse-db:
    image: postgres:16
    container_name: easyql-warehouse-db
    environment:
      POSTGRES_DB: warehouse_db
      POSTGRES_USER: easyql
      POSTGRES_PASSWORD: easyql
    ports:
      - "5435:5432"   # host 5435 → container 5432; avoids conflict with a Postgres already running on host 5432
    volumes:
      - warehouse-db-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U easyql -d warehouse_db"]
      interval: 5s
      timeout: 3s
      retries: 10

  vectors-db:
    image: pgvector/pgvector:pg16
    container_name: easyql-vectors-db
    environment:
      POSTGRES_DB: vectors_db
      POSTGRES_USER: easyql
      POSTGRES_PASSWORD: easyql
    ports:
      - "5433:5432"
    volumes:
      - vectors-db-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U easyql -d vectors_db"]
      interval: 5s
      timeout: 3s
      retries: 10

  traces-db:
    image: postgres:16
    container_name: easyql-traces-db
    environment:
      POSTGRES_DB: traces_db
      POSTGRES_USER: easyql
      POSTGRES_PASSWORD: easyql
    ports:
      - "5434:5432"
    volumes:
      - traces-db-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U easyql -d traces_db"]
      interval: 5s
      timeout: 3s
      retries: 10

volumes:
  warehouse-db-data:
  vectors-db-data:
  traces-db-data:
```

- [ ] **Step 2.2: Verify compose syntax**

Run: `docker compose -f infra/docker-compose.yml config -q`
Expected: exit code 0, no output (config valid).

- [ ] **Step 2.3: Start the stack and confirm health**

Run: `docker compose -f infra/docker-compose.yml up -d`
Then run: `docker compose -f infra/docker-compose.yml ps`
Expected: three services listed, all with `(healthy)` status (may take 10-15s on first run).

- [ ] **Step 2.4: Sanity-check each instance answers Postgres**

Run:
```bash
docker exec easyql-warehouse-db psql -U easyql -d warehouse_db -c 'select 1'
docker exec easyql-vectors-db   psql -U easyql -d vectors_db   -c 'select 1'
docker exec easyql-traces-db    psql -U easyql -d traces_db    -c 'select 1'
```
Expected: each prints `?column?` / `---` / ` 1` / `(1 row)`.

- [ ] **Step 2.5: Commit**

```bash
git add infra/docker-compose.yml
git commit -m "infra: docker-compose with warehouse / vectors / traces postgres instances"
```

---

## Task 3: `application.yml` with warehouse datasource and Flyway config

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 3.1: Replace placeholder `application.yml`**

```yaml
spring:
  application:
    name: easy-ql
  datasource:
    url: jdbc:postgresql://localhost:5435/warehouse_db
    username: easyql
    password: easyql
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 10
      pool-name: warehouse-pool
  flyway:
    enabled: true
    locations: classpath:db/warehouse
    schemas: public
    baseline-on-migrate: false

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health, info
  endpoint:
    health:
      show-details: when-authorized

logging:
  level:
    com.soumyajit.easyql: DEBUG
    org.springframework.web: INFO
```

The single `spring.datasource` block configures `warehouse_db`. When `vectors_db` and `traces_db` are wired into Spring in later plans, this stays the primary datasource and the others are added as additional `@Bean DataSource` definitions.

- [ ] **Step 3.2: Verify build still passes**

Run: `mvn compile -q`
Expected: exit code 0.

- [ ] **Step 3.3: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "config: wire warehouse datasource and flyway to application.yml"
```

---

## Task 4: First Flyway migration — `V1__baseline.sql`

**Files:**
- Create: `src/main/resources/db/warehouse/V1__baseline.sql`

This migration creates a single `_easyql_meta` table that records project metadata. It proves the full migration path works end-to-end and gives Plan 2's real warehouse migration something to follow.

- [ ] **Step 4.1: Create migration file**

```sql
-- V1__baseline.sql
-- Baseline migration. Records project metadata so the migration pipeline is
-- proven end-to-end before the real warehouse schema lands in Plan 2.

create table _easyql_meta (
    key         text primary key,
    value       text not null,
    created_at  timestamptz not null default now()
);

insert into _easyql_meta (key, value) values
    ('schema_version_baseline', 'V1'),
    ('project',                 'easy-ql'),
    ('plan_origin',             'plan-1-foundation');
```

- [ ] **Step 4.2: Commit**

```bash
git add src/main/resources/db/warehouse/V1__baseline.sql
git commit -m "db: V1 baseline migration with _easyql_meta table"
```

---

## Task 5: Health endpoint with WebMvcTest (no database required)

**Files:**
- Create: `src/main/java/com/soumyajit/easyql/api/HealthController.java`
- Create: `src/test/java/com/soumyajit/easyql/api/HealthControllerTest.java`

The Spring Boot actuator already provides `/actuator/health`, but a dedicated `/health` endpoint owned by the app gives us a place to add agent-specific health signals later (e.g., embedding-service reachability) without colliding with actuator semantics.

- [ ] **Step 5.1: Write the failing test**

Create `src/test/java/com/soumyajit/easyql/api/HealthControllerTest.java`:

```java
package com.soumyajit.easyql.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
class HealthControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void healthReturnsStatusUpAndAppName() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.app").value("easy-ql"));
    }
}
```

- [ ] **Step 5.2: Run test, expect failure**

Run: `mvn test -Dtest=HealthControllerTest -q`
Expected: FAIL with `NoSuchBeanDefinitionException` for `HealthController` (the class does not exist yet).

- [ ] **Step 5.3: Implement the controller**

Create `src/main/java/com/soumyajit/easyql/api/HealthController.java`:

```java
package com.soumyajit.easyql.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(
                "status", "UP",
                "app", "easy-ql"
        );
    }
}
```

- [ ] **Step 5.4: Run test, expect pass**

Run: `mvn test -Dtest=HealthControllerTest -q`
Expected: PASS — `Tests run: 1, Failures: 0, Errors: 0`.

- [ ] **Step 5.5: Commit**

```bash
git add src/main/java/com/soumyajit/easyql/api/HealthController.java src/test/java/com/soumyajit/easyql/api/HealthControllerTest.java
git commit -m "feat(api): /health endpoint returning UP + app name"
```

---

## Task 6: Warehouse connection integration test (Testcontainers)

**Files:**
- Create: `src/test/java/com/soumyajit/easyql/WarehouseConnectionIT.java`
- Create: `src/test/resources/application-test.yml`

A separate `WarehouseDataSourceConfig` `@Bean` is NOT needed in Plan 1 — Spring Boot's auto-configuration wires the single datasource from `application.yml`. An explicit config class only becomes necessary when multiple datasources are introduced in Plan 3.

This test brings up a Postgres container via Testcontainers, lets Flyway run `V1__baseline.sql`, then asserts the `_easyql_meta` table exists with the expected rows. It proves the *entire* warehouse wiring path — driver, datasource, Flyway, migrations.

- [ ] **Step 6.1: Create test-only config**

Create `src/test/resources/application-test.yml`:

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/warehouse
    schemas: public
    baseline-on-migrate: false

logging:
  level:
    com.soumyajit.easyql: DEBUG
    org.flywaydb: INFO
```

The test overrides the datasource URL/credentials at runtime via Testcontainers; everything else carries over.

- [ ] **Step 6.2: Write the failing integration test**

Create `src/test/java/com/soumyajit/easyql/WarehouseConnectionIT.java`:

```java
package com.soumyajit.easyql;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class WarehouseConnectionIT {

    @Container
    static PostgreSQLContainer<?> warehouse = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("warehouse_db")
            .withUsername("easyql")
            .withPassword("easyql");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", warehouse::getJdbcUrl);
        registry.add("spring.datasource.username", warehouse::getUsername);
        registry.add("spring.datasource.password", warehouse::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void baselineMigrationCreatesMetaTableWithExpectedRows() {
        Integer rowCount = jdbc.queryForObject(
                "select count(*) from _easyql_meta", Integer.class);
        assertThat(rowCount).isEqualTo(3);

        String projectName = jdbc.queryForObject(
                "select value from _easyql_meta where key = 'project'",
                String.class);
        assertThat(projectName).isEqualTo("easy-ql");
    }
}
```

- [ ] **Step 6.3: Run test, expect pass on first run**

Run: `mvn test -Dtest=WarehouseConnectionIT -q`
Expected: PASS. Testcontainers pulls `postgres:16` on first run (takes 1-2 minutes the first time); subsequent runs are fast. Flyway applies `V1__baseline.sql`, the test reads the inserted rows.

If the test FAILS with `JdbcSQLNonTransientConnectionException`, ensure Docker is running and that `org.testcontainers.junit.jupiter` and `org.testcontainers.postgresql` are on the test classpath (verify with `mvn dependency:tree -Dincludes=org.testcontainers`).

- [ ] **Step 6.4: Run the full test suite to confirm no regressions**

Run: `mvn verify -q`
Expected: both `HealthControllerTest` (Surefire, `*Test.java`) and `WarehouseConnectionIT` (Failsafe, `*IT.java`) run; `BUILD SUCCESS`. `mvn test` alone does NOT run integration tests by Surefire/Failsafe convention — use `mvn verify` for the full suite.

- [ ] **Step 6.5: Commit**

```bash
git add src/test/java/com/soumyajit/easyql/WarehouseConnectionIT.java src/test/resources/application-test.yml
git commit -m "test(it): warehouse connection + V1 baseline migration via testcontainers"
```

---

## Task 7: README skeleton

**Files:**
- Create: `README.md`

The README will grow with every plan into the decision log and demo guide (per spec §11). In Plan 1 it gets the minimum: project intent, build/run instructions, and a forward-looking "what's not built yet" section so a recruiter scanning the early commits sees the deliberate scope.

- [ ] **Step 7.1: Create `README.md`**

```markdown
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
curl -s http://localhost:8090/health | jq
# → {"status":"UP","app":"easy-ql"}
```

## Test

```bash
mvn test
```

Runs the unit and integration tests. Testcontainers will pull `postgres:16` the first time.

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
  config/                       Spring configuration
src/main/resources/
  application.yml               app config (datasources, flyway, server)
  db/warehouse/                 Flyway migrations for warehouse_db
infra/
  docker-compose.yml            Local Docker stack
docs/superpowers/
  specs/                        Design specs
  plans/                        Implementation plans (one per delivery slice)
```
```

- [ ] **Step 7.2: Commit**

```bash
git add README.md
git commit -m "docs: README skeleton with Plan 1 deliverables, prerequisites, and run instructions"
```

---

## Task 8: End-to-end manual smoke + push to remote

**Files:** none

This task performs the end-to-end manual verification the README promises a new contributor can perform. No code changes.

- [ ] **Step 8.1: Clean local state**

Run:
```bash
docker compose -f infra/docker-compose.yml down -v
mvn clean
```

- [ ] **Step 8.2: Bring up the stack**

Run: `docker compose -f infra/docker-compose.yml up -d`
Wait for healthchecks (`docker compose -f infra/docker-compose.yml ps` should show all `(healthy)`).

- [ ] **Step 8.3: Boot the app**

Run: `mvn spring-boot:run`
Expected log lines:
- `Started EasyqlApplication in N seconds`
- A Flyway block reporting `Successfully applied 1 migration to schema "public"` (or similar).

- [ ] **Step 8.4: Verify `/health`**

In another terminal:
```bash
curl -s http://localhost:8090/health
```
Expected: `{"status":"UP","app":"easy-ql"}`

- [ ] **Step 8.5: Verify migration ran against the live DB**

```bash
docker exec easyql-warehouse-db psql -U easyql -d warehouse_db -c "select * from _easyql_meta;"
```
Expected: three rows including `('project', 'easy-ql', ...)`.

- [ ] **Step 8.6: Shut down**

In the `mvn spring-boot:run` terminal, Ctrl-C.
Then: `docker compose -f infra/docker-compose.yml down`

- [ ] **Step 8.7: Push to remote**

```bash
git push origin main
```

Plan 1 is complete when the remote shows the build- and run-instructions in the README and at least 7 commits on `main`.

---

## What this plan does NOT do (deliberately deferred to later plans)

- No warehouse schema beyond `_easyql_meta` (Plan 2).
- No data generation (Plan 2).
- No `vectors_db` schema, no pgvector extension installation (Plan 3).
- No CastAI / Spring AI integration (Plan 3+).
- No retrieval, no worker, no verifier, no executor, no `/ask` (Plans 3-5).
- No Langfuse, no Grafana, no observability layer (Plan 7).
- No BIRD harness (Plan 8).
- No eval sets (Plan 9).

Each of these has a forward reference in spec §3.1 (architecture) and a roadmap entry in spec §2 (in-scope vs deferred).

---

## Acceptance criteria

Plan 1 is complete when **all** of the following are true:

1. `mvn clean verify` passes from a fresh clone with Docker running (Testcontainers pulls images). Surefire runs unit tests (`*Test.java`); Failsafe runs integration tests (`*IT.java`).
2. `docker compose -f infra/docker-compose.yml up -d` brings up three healthy Postgres instances.
3. `mvn spring-boot:run` boots the app and Flyway applies `V1__baseline.sql` to `warehouse_db`.
4. `curl http://localhost:8090/health` returns `{"status":"UP","app":"easy-ql"}`.
5. The README's "Run the full stack locally" section can be followed top-to-bottom by a stranger with the prerequisites installed and produces the expected outputs at each step.
6. All commits are pushed to `origin/main`.

---

## Notes for the executor

- **Frequent commits:** every task ends in a commit. The skill is "Frequent Commits" (per writing-plans). Do not batch.
- **No Co-Authored-By trailer:** this user has memory `no-claude-coauthor-in-commits` — never add an AI co-author trailer.
- **TDD discipline:** write the failing test first; run it; see it fail with the *expected* error; only then implement. This is the rigid skill, not the flexible one (per writing-plans).
- **Integration tests use Testcontainers, not the docker-compose stack.** They are independent. Plan 1's compose file is for `mvn spring-boot:run` and manual verification only.

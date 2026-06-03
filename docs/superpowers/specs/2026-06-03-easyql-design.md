# EasyQL — Text-to-SQL Agent with a Verifier Loop

**Status:** Draft for review
**Date:** 2026-06-03
**Author:** Soumyajit Podder

---

## 1. Overview

EasyQL is a Text-to-SQL agent that translates natural-language questions into executable SQL against a large enterprise-shaped database. A worker LLM generates SQL grounded in retrieved schema; a verifier agent then runs a deterministic-checks-then-LLM pipeline to validate the SQL, and triggers a repair loop when checks fail.

**Project thesis:** the verifier loop is the centerpiece — the BIRD benchmark number is the proof, the verifier is the production-correctness story. The interview pitch leads with reliability engineering, not the benchmark score.

**Why this project**

- Vector retrieval is *mandatory*, not bolted on: an enterprise-scale schema (~80 tables / ~600 columns) does not fit in any single LLM context window, so retrieval is load-bearing.
- The eval is verifiable on labeled data (BIRD execution accuracy) *and* defensible on unlabeled data (verifier pass-rate as a production proxy), which is the realistic enterprise case.
- All seven production-LLM engineering pillars apply naturally: evals, observability, cost engineering, reliability/guardrails, retrieval quality, decision log, Spring AI as the JVM differentiator.

---

## 2. Goals & Non-Goals

### In scope for v1

- Worker agent that generates SQL from a natural-language question grounded in retrieved schema context.
- Verifier agent: deterministic pipeline (parse → schema groundedness → dry-execute → plan heuristics → live-execute) followed by an LLM verifier that judges semantic correctness.
- Repair loop with a 3-round cap; falls back to a `confidence_low`-flagged answer at max iterations.
- Explicit abstention for genuinely out-of-scope questions.
- Hierarchical schema retrieval (table-level → column-level) over pgvector, plus exemplar-question embeddings for the custom warehouse.
- Two-layer answer cache (exact + semantic, with verifier still running on cache hits).
- Automatic OpenAI prompt caching via prompt structure.
- Per-request tracing in self-hosted Langfuse; metrics dashboarded in Grafana.
- BIRD-dev evaluation via the official Python harness behind a thin shim.
- Four-layer eval on the custom warehouse: 30-question hand-labeled smoke set, fault-injection adversarial set, continuous production-style metrics, ~15 sentinel canaries on a schedule.
- README that ships as a decision log — every architectural choice has a one-line rationale and at least one negative-result entry.

### Explicitly deferred (v2 backlog, named in README)

- Multi-model routing across tiers (cheap/standard/strong).
- Local-model fallback for the cheap tier.
- Reasoning-model (e.g. `o3-mini`-class) for repair-round-3 escalation.
- Self-consistency scoring (N-best agreement on results).
- NL round-trip validation (LLM-as-judge on SQL described back in English).
- Property-based / invariant testing on result values.
- Active-learning loop feeding low-confidence queries back into the smoke set.
- Streaming API and chat-style multi-turn refinement.
- Side-by-side retrieval-quality experiment against Qdrant (small benchmark for the decision log; not a primary code path).

---

## 3. Architecture

### 3.1 System shape — Modular monolith (Spring Boot)

One Spring Boot application with strong internal module boundaries. Each module exposes an interface that its implementations sit behind so seams remain clean.

```
easy-ql/                                 (project root)
  src/main/java/.../easyql/
    api/             REST endpoints
    retrieval/       embedding + pgvector access + retrieval strategies
    schema/          schema introspection + table descriptions + exemplar Q&A
    worker/          SQL-generation LLM calls + prompt assembly
    verifier/        deterministic pipeline + LLM verifier + repair loop
    executor/        query execution against read replica + EXPLAIN
    cache/           exact-match + semantic answer caches
    observability/   Langfuse integration + metric export
    eval/            internal eval runners (smoke set, adversarial set, sentinels)
  python/
    bird_eval/       BIRD official harness wrapper; calls EasyQL REST API
  infra/
    docker-compose.yml
  docs/
    superpowers/specs/   design docs
  README.md
```

**Decision log:** modular monolith chosen over multi-service. At expected scale (single-tenant portfolio demo, < 100 RPS hypothetical), distributed coupling costs exceed clarity gains. Each module's interface seam supports later extraction if scale ever justified it.

### 3.2 Data flow per `POST /ask`

```
POST /ask {question, schema_id}
  → embed(question)                                   [retrieval.embed span]
  → pgvector.search(top_k_tables=5, top_k_columns=15) [retrieval.search span]
  → cache.lookup(question, schema_version)            [cache.lookup span]
      hit  → still run verifier on cached SQL → return
      miss → continue
  → worker.generate(question, retrieved_schema)       [worker.call.round_0]
  → verifier.pipeline(sql, retrieved_schema, question)
      stage 1: parse                                  [verifier.stage_1_parse]
      stage 2: schema groundedness                    [verifier.stage_2_schema]
      stage 3: dry execute (EXPLAIN)                  [verifier.stage_3_dry_exec]
      stage 4: plan heuristics                        [verifier.stage_4_plan]
      stage 5: live execute (LIMIT 100, 5s timeout)   [verifier.stage_5_live_exec]
      stage 6: LLM verifier (sees SQL + sampled rows) [verifier.stage_6_llm]
  → on reject AND rounds<3:
      worker.regenerate(critique)                     [worker.call.round_N]
      loop to verifier
  → on reject AND rounds==3:
      return last attempt with confidence=low
  → on stage-2 fail with empty retrieval:
      abstain ("question out of scope for this schema")
  → cache.store(question, sql, result)
  → respond {sql, result, confidence, repair_rounds, trace_id}
```

### 3.3 Persistence

| Database | Purpose | Engine |
|---|---|---|
| `warehouse_db` | The SaaS data being queried (BIRD DBs or custom warehouse) | Postgres |
| `vectors_db` | Schema embeddings, exemplar embeddings, answer cache | Postgres + pgvector |
| `traces_db` | Langfuse-managed | Postgres |

Separate instances (same engine) so the agent's retrieval/observability plane is isolated from the data plane.

### 3.4 API surface (v1)

| Endpoint | Purpose |
|---|---|
| `POST /ask` | Synchronous text-to-SQL request |
| `GET /traces/{trace_id}` | Surface a trace summary (delegates to Langfuse) |
| `POST /eval/smoke` | Trigger a run of the custom-warehouse smoke set |
| `POST /eval/adversarial` | Trigger a verifier-only run on the fault-injection set |
| `GET /health` | Standard liveness/readiness |

Streaming, multi-turn, and authentication are out of v1.

---

## 4. Worker Agent

### 4.1 Responsibilities

Generate a single SELECT-only SQL statement against the retrieved schema. Receives:

- The natural-language question
- The retrieved schema subset (top tables + their relevant columns)
- For repair rounds: the previous SQL and the verifier's structured critique

### 4.2 Prompt structure

Prompt is laid out **static-prefix-first** to maximize OpenAI's automatic prompt caching (which kicks in at 1024+ token prefixes):

1. System instructions (static across all requests) — role, output format, hard constraints (SELECT only, no mutations, no nested DDL, etc.)
2. Retrieved schema subset (changes per-question but identical across repair rounds within a question, so cached intra-question)
3. The question itself
4. (Repair rounds only) the previous SQL and critique

### 4.3 Model

v1: `gpt-4.1` via the castai gateway (`https://llm.cast.ai/openai/v1`). Configured via Spring AI's `OpenAiChatModel` with overridden `base-url`. API key sourced from environment (`CAST_AI_OPENAI_API_KEY`); never committed.

Temperature 0 for determinism.

### 4.4 Output contract

JSON: `{"sql": "<query>", "reasoning": "<one-paragraph explanation>"}`. The reasoning field is for traceability in Langfuse, not user-facing.

---

## 5. Verifier Agent — the thesis

### 5.1 Pipeline

Deterministic checks run first because they are free, fast, and reject roughly half of bad outputs before any LLM verifier call. Each stage emits a Langfuse span and structured pass/fail data.

| Stage | Check | Cost | Catches |
|---|---|---|---|
| 1 | Parse via JSqlParser (or `PREPARE` round-trip) | ~1 ms | Syntactic errors |
| 2 | Schema groundedness — every referenced table/column exists in retrieved subset | ~5 ms | Hallucinated columns (the #1 worker failure mode) |
| 3 | `EXPLAIN (ANALYZE false)` against read replica | ~50–500 ms | Type errors, ambiguous columns, illegal aggregations |
| 4 | Plan heuristics on the EXPLAIN output | ~1 ms | Cartesian products, seq-scan on huge tables when index exists, missing WHERE in mutating queries, `SELECT *` |
| 5 | Live execute with `LIMIT 100` and 5 s wall-clock timeout | ~100 ms–5 s | Runtime errors, timeouts |
| 6 | LLM verifier (only survivors reach here) | ~1 LLM call | Semantic mismatch between result and question, obviously-missing joins/filters, suboptimal-but-correct approach |

### 5.2 LLM verifier specifics

- Sees: the original question, the retrieved schema subset, the worker's SQL, the executed result (sampled top 10 rows).
- Model: `gpt-4.1`, temperature 0.
- Output contract: structured JSON `{"accept": bool, "failure_type": "<enum>", "critique": "<targeted suggestion>"}`. Failure types map directly to repair-loop prompt templates so the worker's regeneration is targeted, not generic.

### 5.3 Repair loop

- Max 3 rounds (worker → verifier → revise → verifier → revise → verifier). Three is the standard from actor-critic literature; beyond that marginal gains drop and cost compounds.
- Critique format includes the specific stage that failed plus a structured suggestion.
- At max iterations: return the last attempt with `confidence=low` rather than refusing. Refusal inflates abstention rate and obscures the failure mode in eval data.

### 5.4 Abstention

If stage 2 (schema groundedness) fails *and* retrieval returned no chunks above a similarity threshold (default cosine ≥ 0.35, tunable per dataset), the question is out-of-scope for this DB. Refuse explicitly: `"This question cannot be answered from the available schema."` Abstention precision is measured against a curated out-of-scope question set in eval. The similarity threshold itself is a hyperparameter tuned during the smoke-set correlation experiment.

---

## 6. Retrieval Strategy

### 6.1 What gets embedded

**For BIRD databases (descriptions provided):**

- One table-level vector per table (name + description + column-name dump)
- One column-level vector per column (table.column + description + sample values where available)

**For the custom warehouse (self-authored):**

- Same hierarchical structure
- Plus 3–5 hand-written exemplar questions per table, embedded as additional retrieval anchors

**Decision-log story to publish:** measure hit-rate@5 with and without exemplar embeddings on the smoke set. Expectation: exemplars materially improve retrieval because they look more like user questions than schema descriptions do.

### 6.2 Retrieval algorithm

1. Embed the question with `text-embedding-3-small` via castai.
2. Search table-level vectors → top 5 candidate tables.
3. Search column-level vectors *scoped to those candidate tables* → top 15 columns.
4. Assemble a minimal schema context: candidate tables, their columns, FK relationships among them.
5. Pass to worker.

### 6.3 Vector store

Primary: **pgvector** on its own Postgres instance, HNSW index. Spring AI's `PgVectorStore` provides first-class support.

Secondary experiment (v2 backlog but mentioned in README decision log): port retrieval to Qdrant for a side-by-side numbers comparison.

---

## 7. Cost & Observability

### 7.1 Observability stack

- **Langfuse, self-hosted** via Docker (open-source; Java SDK). One Langfuse `trace` per `/ask` request; spans per pipeline stage.
- **Grafana** dashboards over Langfuse-exported metrics (Postgres queries against `traces_db`).

### 7.2 Per-trace tags (for slicing in Langfuse + Grafana)

- `dataset`: `bird_dev` | `bird_test` | `custom_warehouse`
- `bird_difficulty`: `simple` | `moderate` | `challenging` (BIRD provides)
- `knowledge_hints`: `on` | `off`
- `worker_model`: v1 always `gpt-4.1`
- `repair_rounds_used`: 0 | 1 | 2 | 3
- `outcome`: `accepted` | `confidence_low` | `abstained` | `error`
- `cache_hit`: `none` | `exact` | `semantic`

### 7.3 Cost levers in v1

| Lever | Mechanism | Story |
|---|---|---|
| Automatic prompt caching | Static system + schema prefix in worker/verifier prompts triggers OpenAI auto-cache at 1024+ tokens; tracked via `usage.prompt_tokens_details.cached_tokens` | Measure cache-hit ratio; expect high hits across repair rounds within a question and across questions sharing a schema subset |
| Exact-match answer cache | Hash of `(question, schema_version)` → cached SQL + result | Massive win during BIRD eval re-runs |
| Semantic answer cache | Question embedding → nearest-neighbor in cache (cosine ≥ 0.95), same `schema_version` only | **Verifier still runs on cache hits** — semantic match is fuzzy and unsafe to trust blindly. Net cost saved = worker call only. Decision-log entry honestly reports this was a smaller win than expected. |
| Verifier loop convergence | Reporting % of queries accepted in round 0 vs needing rounds 1/2/3 | Each additional repair round is real cost; converging early is the saving |

### 7.4 Metrics published in README + Grafana

**Quality**
- BIRD-dev execution accuracy, split (with-hints / without-hints) × (simple / moderate / challenging) — six numbers
- Verifier recall on the adversarial / fault-injection set
- Verifier precision on the smoke set
- Abstention precision on the curated out-of-scope set

**Cost**
- $/query: P50, P95
- Token breakdown: prompt / completion / cached_read per query
- Cost-per-accepted-answer by `repair_rounds_used`
- Cache hit rates: prompt cache, exact answer cache, semantic answer cache

**Latency**
- End-to-end P50, P95
- Stage breakdown: retrieval / worker / verifier-deterministic / verifier-LLM / repair-overhead
- Repair-round distribution histogram

---

## 8. Evaluation Strategy

### 8.1 BIRD evaluation

- BIRD-dev split, official harness in Python.
- Report execution accuracy under both knowledge-hint settings (on/off) and per BIRD's three difficulty tiers.
- The README declares the exact configuration: dev split, hint flag, execution-accuracy not exact-match. This precision is itself a senior signal in interviews — anyone who knows BIRD will probe these specifics.
- BIRD-test reserved (not used during development) to avoid overfitting; one-shot run at the end with results recorded.

### 8.2 Custom warehouse evaluation — four layers

**Layer 1 — Smoke set (30 hand-labeled questions)**
- Authored by hand, ranging across difficulty (simple lookup → 3-table join → analytical aggregation with window functions).
- Each question has hand-written ground-truth SQL plus the expected result shape.
- Purpose: validate that **verifier-pass-rate correlates with actual execution accuracy**. The smoke set is the trust anchor for the verifier-as-production-eval claim.
- Sample size chosen for defensibility: every question is one you can talk through in detail in an interview.

**Layer 2 — Adversarial / fault-injection set (~60 deliberately broken variants per smoke question)**
- Mutations: hallucinated columns, wrong joins, missing WHERE, off-by-one aggregations, swapped operators, suspicious wildcard patterns.
- Run only the verifier on these (worker bypassed) — measures **verifier recall** in isolation.
- Combined with smoke-set false positives, gives the verifier-precision/recall story.

**Layer 3 — Production-style continuous metrics (no labels needed)**
- Verifier-pass-rate trend over time, alerts on regression
- Abstention rate (too low = hallucinating, too high = unhelpful)
- Repair-rounds distribution drift
- Latency / cost percentile drift
- Retrieval hit-rate@k against schema embeddings
- Surfaced in Grafana dashboards screenshot-ready for the README.

**Layer 4 — Sentinel set (~15 canary questions on a schedule)**
- Known-good questions run periodically against the live agent; logs loudly on failure. Cheap, catches silent degradation.

---

## 9. Datasets

### 9.1 BIRD

- Loaded via the official BIRD dataset distribution; databases provisioned into `warehouse_db` as separate schemas.
- Schema descriptions ingested into `vectors_db` at startup.

### 9.2 Custom B2B SaaS warehouse schema

**Domain:** multi-tenant B2B SaaS, mirroring a realistic enterprise data warehouse.

**Approximate table groups (target ~80 tables, ~600 columns):**

- Identity & tenancy (~10): orgs, org_settings, users, user_profiles, roles, role_assignments, permissions, role_permissions, sso_configurations, api_keys
- Subscriptions & billing (~14): plans, plan_features, plan_prices, subscriptions, subscription_addons, billing_accounts, payment_methods, invoices, invoice_line_items, payments, refunds, dunning_events, tax_records, credit_notes
- Product & usage (~13): products, product_modules, product_features, feature_flags, feature_flag_overrides, feature_flag_targets, usage_events, sessions, session_events, api_calls, api_quota_buckets, rate_limits, integrations
- Customer success (~9): support_tickets, ticket_comments, ticket_attachments, ticket_status_history, csat_responses, churn_signals, churn_predictions, nps_responses, nps_followups
- Analytics aggregates — denormalized enterprise wart (~11): daily_active_users, weekly_active_users, monthly_active_users, monthly_recurring_revenue, arr_snapshots, churn_cohort_facts, feature_adoption_rollups, retention_curves, ltv_by_cohort, expansion_revenue, contraction_revenue
- Operational audit (~9): api_audit_log, billing_audit_log, admin_action_log, security_events, login_attempts, gdpr_deletion_requests, data_export_requests, schema_migrations_log, system_health_events
- Reference / legacy carry-over (~7): countries, currencies, tax_jurisdictions, deprecated_v1_users (kept for backfill — the kind of legacy wart real warehouses have), deprecated_v1_subscriptions, timezone_lookup, industry_codes
- External integrations data (~8): salesforce_accounts, hubspot_contacts, stripe_customers, slack_workspaces, webhook_deliveries, sync_state, integration_errors, integration_credentials_audit

**Total: ~81 tables**, fitting the "doesn't fit in context" pressure-test target.

**Deliberate enterprise warts** (so retrieval has to do real work):

- Legacy column names that survived migrations (`is_active` next to `status`, `created_at` next to `dt_created`)
- Both normalized facts and denormalized aggregates that answer overlapping questions differently
- Multiple "user" tables (employees, end-users, system-users) requiring disambiguation
- Multi-tenant scoping columns (`org_id`) that must appear in nearly every query

**Data generation:** Faker + a deterministic seed script that creates internally consistent records (subscriptions reference real plans; invoices reference real subscriptions; usage events reference real users in real orgs).

---

## 10. Technical Stack

| Layer | Choice | Why |
|---|---|---|
| Language / framework | Java 21 + Spring Boot 3.x + Spring AI | The differentiator. JVM enterprise audience. |
| LLM | `gpt-4.1` via castai gateway (OpenAI-compatible) | Matches mds_support_rag pattern; one credential; multi-model routing deferred to v2 |
| Embeddings | `text-embedding-3-small` via castai | Same gateway as LLM; better retrieval baseline than local models at trivial cost |
| Vector store | pgvector (Postgres + HNSW), via Spring AI `PgVectorStore` | Already in Postgres for the warehouse; one fewer system; first-class Spring AI support |
| Warehouse / vectors / traces databases | Three separate Postgres instances | Plane isolation (data / retrieval / observability) |
| Observability | Self-hosted Langfuse + Grafana | Open source; better signal than "I have a Langfuse cloud account"; demo-friendly |
| SQL parser | JSqlParser | Deterministic Stage 1 of verifier |
| BIRD eval | Official Python harness wrapped behind a thin FastAPI shim | Standardized eval scoring; reimplementing in Java risks off-by-one numbers |
| Deployment | docker-compose for local demo | `make demo` brings everything up; README ships screenshots |

---

## 11. Decision Log (lives in README, surfaced here for the spec)

Every decision below has a one-line rationale plus, where the decision changed during development or had a negative-result component, a "what failed" note. The README ships the full version; this list is for spec coverage.

- **Modular monolith over microservices.** Distributed coupling costs exceed clarity gains at this scale.
- **Spring AI primary, Python only for BIRD eval harness.** Reinventing BIRD's scoring in Java risks off-by-one numbers; the eval is the one place standardization matters more than language consistency.
- **pgvector primary, Qdrant as a side experiment.** Already in Postgres; the operational simplicity won at this scale.
- **Single model in v1 (`gpt-4.1`), routing deferred to v2.** Tells the cost story via caching + verifier convergence in v1 without rabbit-holing on routing tuning.
- **Verifier sees SQL + sampled result, not just SQL.** Half of real failures are silent-empty-results, not parse errors; worth the extra context.
- **v1: worker and verifier both `gpt-4.1`; v2 introduces asymmetry.** When routing arrives in v2, the verifier stays at the strongest tier while the worker routes by difficulty — the safety net needs an accuracy floor higher than the worker because a same-model verifier shares the worker's blind spots.
- **3-round repair cap, then `confidence_low` not refusal.** Refusal hides the failure mode in eval; flagged-answer is more honest and more diagnosable.
- **Hierarchical retrieval + exemplar questions.** Exemplars look more like user questions than schema descriptions; a measurable RAG-quality story to publish.
- **Smoke set deliberately small (30 questions).** Its purpose is to validate the verifier's correlation with correctness, not to be a representative sample. Verifier metrics (which scale freely) carry the production story.
- **Semantic cache still runs the verifier.** Expected and confirmed negative result: semantic-cache wins were smaller than anticipated because the verifier still ran. Net win is worker-call savings only. Honest negative result; senior judgment signal.

---

## 12. Risks & Open Questions

| Risk / Open Question | Mitigation |
|---|---|
| BIRD score lands materially below leaderboard | The thesis is the verifier loop; BIRD is proof, not the headline. Lead with reliability story. |
| Available castai models limited to a single tier | Routing already deferred to v2; v1 single-model design absorbs this. |
| Verifier-pass-rate does not correlate with smoke-set execution accuracy | Debug verifier (most likely stage 6 prompt) before scaling. The correlation experiment is the gating check before publishing the production-eval claim. |
| Custom warehouse data generation produces non-realistic patterns that don't stress retrieval | Audit the smoke-set questions against generated data early; tighten seed script if joins return zero rows on natural queries. |
| Langfuse self-hosted ops overhead | docker-compose contains the cost; one container, one volume; not a real risk. |
| OpenAI prompt cache hit rate lower than expected | Measure first; if low, restructure prompt ordering. The auto-cache is opaque so a small experiment up front confirms the lever works. |

---

## 13. Success Metrics

EasyQL is interview-ready when the README publishes all of:

1. BIRD-dev execution accuracy under both hint settings, with the leaderboard URL for context
2. Verifier recall on the adversarial set (target: ≥ 80% of seeded bugs caught)
3. Verifier precision on the smoke set (target: ≤ 10% false rejects on correct queries)
4. Correlation report: verifier pass-rate vs execution accuracy on the smoke set
5. Cost-per-query (P50, P95) with prompt-cache and answer-cache hit rates
6. Latency-per-query (P50, P95) broken down by stage
7. Repair-rounds distribution histogram
8. At least one decision-log entry that names a negative result honestly (semantic cache is the planned candidate)
9. Grafana screenshots embedded in README
10. A reproducible `make demo` that brings up the full stack and runs the smoke set against it

---

## 14. Out-of-Scope (Beyond v2 Backlog)

- Multi-DB query federation
- Write/DDL queries
- Multi-turn conversational refinement
- Per-user personalization / memory
- Authentication / multi-tenant agent isolation
- Production HA / horizontal scaling
- Cloud deployment (AWS/GCP) — local Docker only

These are explicit "not now" items, named so an interviewer asking about them gets a clear "deliberately out of scope, here's why" answer.

-- V2__warehouse_schema.sql
-- B2B SaaS warehouse — 20 tables across 8 functional groups.
-- Trimmed from the spec's ~80-table target to keep Plan 2 focused on what
-- drives retrieval pressure rather than on bulk schema repetition.
--
-- Deliberate retrieval-pressure warts encoded here:
--   1. users vs deprecated_v1_users — same domain, different column names
--      (users.email vs deprecated_v1_users.email_addr; created_at vs joined_dt).
--   2. users.status (text enum) + users.is_active (boolean) — redundant fields
--      that look like they should be derivable from each other but in real
--      schemas drift; the worker has to know which is canonical for which query.
--   3. Three raw/rollup pairs:
--        invoices + payments          ↔ monthly_recurring_revenue
--        usage_events                 ↔ feature_adoption_rollups
--        churn_signals                ↔ churn_cohort_facts
--      Same business question has two valid paths; retrieval must surface both.
--   4. role_assignments forces a 3-way join (users + role_assignments + roles)
--      for "who has admin permissions" style questions.
--   5. Multi-tenant filter: org_id cascades into nearly every tenant table.
--   6. Cross-system: stripe_customers.stripe_customer_id is a different ID
--      space than users.id; joins must hop through stripe_customers.

-- ----------------------------------------------------------------------------
-- Independent / parent tables first (FK-ordering)
-- ----------------------------------------------------------------------------

create table orgs (
    id           bigint generated always as identity primary key,
    name         text not null,
    slug         text not null unique,
    tier         text not null,                        -- 'free' | 'pro' | 'business' | 'enterprise'
    industry     text,
    country_code char(2),
    created_at   timestamptz not null default now()
);

create table plans (
    id              bigint generated always as identity primary key,
    name            text not null,
    code            text not null unique,              -- 'starter' | 'team' | 'business' | 'enterprise'
    monthly_price   numeric(10, 2) not null,
    annual_price    numeric(10, 2) not null,
    seats_included  int not null,
    is_active       boolean not null default true,
    created_at      timestamptz not null default now()
);

create table roles (
    id          bigint generated always as identity primary key,
    name        text not null unique,                  -- 'admin' | 'editor' | 'viewer' | 'billing_manager'
    description text,
    created_at  timestamptz not null default now()
);

create table products (
    id              bigint generated always as identity primary key,
    name            text not null,
    code            text not null unique,
    launched_on     date not null,
    is_deprecated   boolean not null default false,
    created_at      timestamptz not null default now()
);

-- Legacy table retained for backfill — DELIBERATE WART (#1).
-- Note the divergent column names from the modern `users` table.
create table deprecated_v1_users (
    id                  bigint generated always as identity primary key,
    email_addr          text not null,                 -- WART: users.email is the modern column
    full_name           text,                          -- WART: users.name is single-field
    joined_dt           date,                          -- WART: users.created_at is the modern column
    migrated_to_user_id bigint,                        -- back-reference filled after migration; not FK-enforced
    archived_at         timestamptz not null default now()
);

-- ----------------------------------------------------------------------------
-- Identity (tenant-scoped)
-- ----------------------------------------------------------------------------

create table users (
    id            bigint generated always as identity primary key,
    org_id        bigint not null references orgs(id),
    email         text not null unique,
    name          text not null,                       -- single name, not first/last
    status        text not null default 'active',      -- 'active' | 'suspended' | 'deleted'
    is_active     boolean not null default true,       -- WART (#2): redundant with status
    created_at    timestamptz not null default now(),
    last_login_at timestamptz
);

create table role_assignments (
    id           bigint generated always as identity primary key,
    user_id      bigint not null references users(id),
    role_id      bigint not null references roles(id),
    org_id       bigint not null references orgs(id),  -- WART (#5): tenant scoping
    assigned_at  timestamptz not null default now(),
    unique (user_id, role_id, org_id)
);

-- ----------------------------------------------------------------------------
-- Subscriptions & billing
-- ----------------------------------------------------------------------------

create table subscriptions (
    id            bigint generated always as identity primary key,
    org_id        bigint not null references orgs(id),
    plan_id       bigint not null references plans(id),
    status        text not null,                       -- 'trial' | 'active' | 'paused' | 'cancelled'
    started_at    timestamptz not null,
    cancelled_at  timestamptz,                         -- WART: 'is the sub active?' has two valid paths
    created_at    timestamptz not null default now()
);

create table invoices (
    id              bigint generated always as identity primary key,
    org_id          bigint not null references orgs(id),
    subscription_id bigint not null references subscriptions(id),
    invoice_number  text not null unique,
    status          text not null,                     -- 'draft' | 'sent' | 'paid' | 'overdue' | 'void'
    amount_total    numeric(15, 2) not null,           -- WART: denormalized sum of line items
    currency        char(3) not null default 'USD',
    issued_at       timestamptz not null,
    due_at          timestamptz not null,
    paid_at         timestamptz,
    created_at      timestamptz not null default now()
);

create table invoice_line_items (
    id          bigint generated always as identity primary key,
    invoice_id  bigint not null references invoices(id),
    description text not null,
    quantity    numeric(10, 2) not null,
    unit_price  numeric(10, 2) not null,
    amount      numeric(15, 2) not null,               -- WART: denormalized quantity * unit_price
    created_at  timestamptz not null default now()
);

create table payments (
    id           bigint generated always as identity primary key,
    invoice_id   bigint not null references invoices(id),
    amount       numeric(15, 2) not null,
    currency     char(3) not null default 'USD',
    method       text not null,                        -- 'card' | 'ach' | 'wire'
    status       text not null,                        -- 'pending' | 'succeeded' | 'failed'
    received_at  timestamptz not null,
    created_at   timestamptz not null default now()
);

-- ----------------------------------------------------------------------------
-- Product & usage
-- ----------------------------------------------------------------------------

create table sessions (
    id          bigint generated always as identity primary key,
    user_id     bigint not null references users(id),
    started_at  timestamptz not null,
    ended_at    timestamptz,
    ip_address  inet,
    user_agent  text,
    created_at  timestamptz not null default now()
);

create table usage_events (
    id           bigint generated always as identity primary key,
    org_id       bigint not null references orgs(id),
    user_id      bigint not null references users(id),
    product_id   bigint references products(id),       -- nullable for non-product-scoped events
    event_type   text not null,                        -- 'view' | 'edit' | 'export' | 'invite' | ...
    occurred_at  timestamptz not null,
    metadata     jsonb,
    created_at   timestamptz not null default now()
);

-- ----------------------------------------------------------------------------
-- Customer success
-- ----------------------------------------------------------------------------

create table support_tickets (
    id           bigint generated always as identity primary key,
    org_id       bigint not null references orgs(id),
    user_id      bigint not null references users(id),
    subject      text not null,
    description  text,
    priority     text not null,                        -- 'low' | 'normal' | 'high' | 'urgent'
    status       text not null,                        -- 'open' | 'in_progress' | 'waiting' | 'resolved' | 'closed'
    opened_at    timestamptz not null,
    resolved_at  timestamptz,
    created_at   timestamptz not null default now()
);

create table churn_signals (
    id           bigint generated always as identity primary key,
    org_id       bigint not null references orgs(id),
    signal_type  text not null,                        -- 'usage_drop' | 'support_volume' | 'payment_failure' | 'cancel_intent'
    severity     text not null,                        -- 'low' | 'medium' | 'high'
    detected_at  timestamptz not null,
    resolved_at  timestamptz,
    created_at   timestamptz not null default now()
);

-- ----------------------------------------------------------------------------
-- External integrations (cross-system join target)
-- ----------------------------------------------------------------------------

create table stripe_customers (
    id                     bigint generated always as identity primary key,
    user_id                bigint not null references users(id),
    stripe_customer_id     text not null unique,        -- 'cus_xxx' — different ID space than users.id (WART #6)
    default_payment_method text,
    created_at             timestamptz not null default now()
);

-- ----------------------------------------------------------------------------
-- Analytics aggregates (denormalized rollups — WART #3)
-- ----------------------------------------------------------------------------

create table monthly_recurring_revenue (
    id                bigint generated always as identity primary key,
    org_id            bigint not null references orgs(id),
    month             date not null,                   -- first day of the calendar month
    mrr_usd           numeric(15, 2) not null,
    new_mrr           numeric(15, 2) not null default 0,
    expansion_mrr     numeric(15, 2) not null default 0,
    contraction_mrr   numeric(15, 2) not null default 0,
    churned_mrr       numeric(15, 2) not null default 0,
    computed_at       timestamptz not null default now(),
    unique (org_id, month)
);

create table feature_adoption_rollups (
    id              bigint generated always as identity primary key,
    org_id          bigint not null references orgs(id),
    product_id      bigint not null references products(id),
    week            date not null,                     -- ISO week start (Monday)
    distinct_users  int not null default 0,
    total_events    int not null default 0,
    computed_at     timestamptz not null default now(),
    unique (org_id, product_id, week)
);

create table churn_cohort_facts (
    id                  bigint generated always as identity primary key,
    cohort_month        date not null,                 -- month of org acquisition
    months_since_join   int not null,
    orgs_remaining      int not null,
    retention_pct       numeric(5, 2) not null,
    computed_at         timestamptz not null default now(),
    unique (cohort_month, months_since_join)
);

-- ----------------------------------------------------------------------------
-- Operational audit
-- ----------------------------------------------------------------------------

create table api_audit_log (
    id            bigint generated always as identity primary key,
    user_id       bigint references users(id),         -- nullable for unauthenticated calls
    org_id        bigint references orgs(id),
    request_id    uuid not null,
    method        text not null,                       -- 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
    path          text not null,
    status_code   int not null,
    duration_ms   int not null,
    ip_address    inet,
    requested_at  timestamptz not null
);

-- ----------------------------------------------------------------------------
-- Indexes
-- ----------------------------------------------------------------------------

create index idx_users_org                       on users (org_id);
create index idx_users_status                    on users (status);
create index idx_dep_users_email_addr            on deprecated_v1_users (email_addr);
create index idx_dep_users_migrated_to           on deprecated_v1_users (migrated_to_user_id);
create index idx_role_assignments_user           on role_assignments (user_id);
create index idx_role_assignments_role           on role_assignments (role_id);
create index idx_role_assignments_org            on role_assignments (org_id);
create index idx_subscriptions_org               on subscriptions (org_id);
create index idx_subscriptions_plan              on subscriptions (plan_id);
create index idx_subscriptions_status            on subscriptions (status);
create index idx_invoices_org                    on invoices (org_id);
create index idx_invoices_subscription           on invoices (subscription_id);
create index idx_invoices_status                 on invoices (status);
create index idx_invoices_issued_at              on invoices (issued_at);
create index idx_line_items_invoice              on invoice_line_items (invoice_id);
create index idx_payments_invoice                on payments (invoice_id);
create index idx_payments_status                 on payments (status);
create index idx_payments_received_at            on payments (received_at);
create index idx_sessions_user                   on sessions (user_id);
create index idx_sessions_started_at             on sessions (started_at);
create index idx_usage_events_org_occurred       on usage_events (org_id, occurred_at desc);
create index idx_usage_events_user               on usage_events (user_id);
create index idx_usage_events_product            on usage_events (product_id);
create index idx_usage_events_type               on usage_events (event_type);
create index idx_tickets_org                     on support_tickets (org_id);
create index idx_tickets_user                    on support_tickets (user_id);
create index idx_tickets_status                  on support_tickets (status);
create index idx_tickets_opened_at               on support_tickets (opened_at);
create index idx_churn_signals_org               on churn_signals (org_id);
create index idx_churn_signals_severity          on churn_signals (severity);
create index idx_churn_signals_detected_at       on churn_signals (detected_at);
create index idx_stripe_customers_user           on stripe_customers (user_id);
create index idx_mrr_month                       on monthly_recurring_revenue (month);
create index idx_adoption_week                   on feature_adoption_rollups (week);
create index idx_cohort_facts_cohort             on churn_cohort_facts (cohort_month);
create index idx_audit_user                      on api_audit_log (user_id);
create index idx_audit_org                       on api_audit_log (org_id);
create index idx_audit_requested_at              on api_audit_log (requested_at);
create index idx_audit_path                      on api_audit_log (path);

-- ----------------------------------------------------------------------------
-- Mark this migration in the meta table from V1
-- ----------------------------------------------------------------------------

insert into _easyql_meta (key, value) values
    ('schema_version_warehouse', 'V2'),
    ('warehouse_table_count',    '20');

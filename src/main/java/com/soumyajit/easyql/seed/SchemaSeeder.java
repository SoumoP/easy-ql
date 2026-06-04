package com.soumyajit.easyql.seed;

import net.datafaker.Faker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Generates synthetic data for the 20-table B2B SaaS warehouse.
 *
 * Active only when the {@code seed} Spring profile is enabled. Invoke with:
 *   SPRING_PROFILES_ACTIVE=seed mvn spring-boot:run
 *
 * Idempotent: TRUNCATEs all 20 tables (CASCADE, RESTART IDENTITY) before
 * inserting. Same input seed produces the same data, so seeded values are
 * reproducible across machines and CI runs.
 */
@Component
@Profile("seed")
public class SchemaSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaSeeder.class);

    private static final int N_ORGS = 20;
    private static final int N_USERS = 200;
    private static final int N_DEPRECATED_USERS = 80;
    private static final int N_PRODUCTS = 5;
    private static final int N_SUBSCRIPTIONS = 25;
    private static final int N_INVOICES = 500;
    private static final int N_LINE_ITEMS_PER_INVOICE_MAX = 4;
    private static final double PAYMENT_RATE = 0.9;
    private static final int N_SESSIONS = 5_000;
    private static final int N_USAGE_EVENTS = 20_000;
    private static final int N_TICKETS = 300;
    private static final int N_CHURN_SIGNALS = 80;
    private static final int N_MRR_MONTHS = 12;
    private static final int N_ADOPTION_WEEKS = 12;
    private static final int N_AUDIT_ROWS = 10_000;

    private final JdbcTemplate jdbc;
    private final Faker faker;

    public SchemaSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.faker = new Faker(new Random(42L));
    }

    @Override
    public void run(String... args) {
        long start = System.currentTimeMillis();
        log.info("SchemaSeeder starting — TRUNCATE + reseed across 20 tables");

        truncateAll();

        List<Long> orgIds = seedOrgs();
        List<Long> planIds = seedPlans();
        List<Long> roleIds = seedRoles();
        List<Long> productIds = seedProducts();
        List<Long> userIds = seedUsers(orgIds);
        seedDeprecatedV1Users(userIds);
        seedRoleAssignments(userIds, roleIds, orgIds);
        List<Long> subscriptionIds = seedSubscriptions(orgIds, planIds);
        List<Long> invoiceIds = seedInvoices(orgIds, subscriptionIds);
        seedInvoiceLineItems(invoiceIds);
        seedPayments(invoiceIds);
        seedSessions(userIds);
        seedUsageEvents(orgIds, userIds, productIds);
        seedSupportTickets(orgIds, userIds);
        seedChurnSignals(orgIds);
        seedStripeCustomers(userIds);
        seedMonthlyRecurringRevenue(orgIds);
        seedFeatureAdoptionRollups(orgIds, productIds);
        seedChurnCohortFacts();
        seedApiAuditLog(userIds, orgIds);

        log.info("SchemaSeeder finished in {} ms", System.currentTimeMillis() - start);
    }

    private void truncateAll() {
        // CASCADE handles FK dependencies; RESTART IDENTITY zeros the bigint sequences.
        jdbc.execute("truncate table " +
                "orgs, plans, roles, products, deprecated_v1_users, users, " +
                "role_assignments, subscriptions, invoices, invoice_line_items, " +
                "payments, sessions, usage_events, support_tickets, churn_signals, " +
                "stripe_customers, monthly_recurring_revenue, feature_adoption_rollups, " +
                "churn_cohort_facts, api_audit_log " +
                "restart identity cascade");
    }

    // -----------------------------------------------------------------------
    // Per-table seeders
    // -----------------------------------------------------------------------

    private List<Long> seedOrgs() {
        String[] tiers = {"free", "pro", "business", "enterprise"};
        String[] industries = {"fintech", "healthtech", "retail", "saas", "logistics", "edtech"};
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < N_ORGS; i++) {
            String name = faker.company().name();
            String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "-") + "-" + i;
            ids.add(jdbc.queryForObject(
                    "insert into orgs (name, slug, tier, industry, country_code, created_at) " +
                    "values (?, ?, ?, ?, ?, ?) returning id",
                    Long.class,
                    name, slug, tiers[i % tiers.length],
                    industries[i % industries.length],
                    faker.country().countryCode2().toUpperCase(),
                    instantInPast(365 * 3)));
        }
        log.info("  orgs: {}", ids.size());
        return ids;
    }

    private List<Long> seedPlans() {
        Object[][] plans = {
                {"Starter",    "starter",    BigDecimal.valueOf(0),    BigDecimal.valueOf(0),       3},
                {"Team",       "team",       BigDecimal.valueOf(49),   BigDecimal.valueOf(490),    10},
                {"Business",   "business",   BigDecimal.valueOf(199),  BigDecimal.valueOf(1990),   50},
                {"Enterprise", "enterprise", BigDecimal.valueOf(999),  BigDecimal.valueOf(9990),  500},
        };
        List<Long> ids = new ArrayList<>();
        for (Object[] p : plans) {
            ids.add(jdbc.queryForObject(
                    "insert into plans (name, code, monthly_price, annual_price, seats_included, is_active) " +
                    "values (?, ?, ?, ?, ?, true) returning id",
                    Long.class, p[0], p[1], p[2], p[3], p[4]));
        }
        log.info("  plans: {}", ids.size());
        return ids;
    }

    private List<Long> seedRoles() {
        String[][] roles = {
                {"admin", "Full access including billing and user management"},
                {"editor", "Can create and modify content"},
                {"viewer", "Read-only access"},
                {"billing_manager", "Billing-only access"},
        };
        List<Long> ids = new ArrayList<>();
        for (String[] r : roles) {
            ids.add(jdbc.queryForObject(
                    "insert into roles (name, description) values (?, ?) returning id",
                    Long.class, r[0], r[1]));
        }
        log.info("  roles: {}", ids.size());
        return ids;
    }

    private List<Long> seedProducts() {
        String[][] products = {
                {"Inbox",       "inbox",       "false"},
                {"Reports",     "reports",     "false"},
                {"Workflows",   "workflows",   "false"},
                {"Insights",    "insights",    "false"},
                {"LegacyExport","legacy_export","true"},
        };
        List<Long> ids = new ArrayList<>();
        for (String[] p : products) {
            ids.add(jdbc.queryForObject(
                    "insert into products (name, code, launched_on, is_deprecated) " +
                    "values (?, ?, ?, ?) returning id",
                    Long.class, p[0], p[1],
                    LocalDate.now().minusDays(faker.number().numberBetween(180, 1500)),
                    Boolean.parseBoolean(p[2])));
        }
        log.info("  products: {}", ids.size());
        return ids;
    }

    private List<Long> seedUsers(List<Long> orgIds) {
        String[] statuses = {"active", "active", "active", "suspended", "deleted"};
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < N_USERS; i++) {
            String status = statuses[faker.number().numberBetween(0, statuses.length)];
            boolean isActive = "active".equals(status);
            ids.add(jdbc.queryForObject(
                    "insert into users (org_id, email, name, status, is_active, created_at, last_login_at) " +
                    "values (?, ?, ?, ?, ?, ?, ?) returning id",
                    Long.class,
                    orgIds.get(faker.number().numberBetween(0, orgIds.size())),
                    "user" + i + "_" + faker.internet().emailAddress(),
                    faker.name().fullName(),
                    status,
                    isActive,
                    instantInPast(365 * 2),
                    isActive ? instantInPast(30) : null));
        }
        log.info("  users: {}", ids.size());
        return ids;
    }

    private void seedDeprecatedV1Users(List<Long> modernUserIds) {
        for (int i = 0; i < N_DEPRECATED_USERS; i++) {
            Long migratedTo = i % 2 == 0
                    ? modernUserIds.get(faker.number().numberBetween(0, modernUserIds.size()))
                    : null;
            jdbc.update(
                    "insert into deprecated_v1_users (email_addr, full_name, joined_dt, migrated_to_user_id, archived_at) " +
                    "values (?, ?, ?, ?, ?)",
                    faker.internet().emailAddress(),
                    faker.name().fullName(),
                    LocalDate.now().minusDays(faker.number().numberBetween(800, 2500)),
                    migratedTo,
                    instantInPast(400));
        }
        log.info("  deprecated_v1_users: {}", N_DEPRECATED_USERS);
    }

    private void seedRoleAssignments(List<Long> userIds, List<Long> roleIds, List<Long> orgIds) {
        // Pull each user's org so the role assignment respects tenancy.
        List<long[]> userOrgs = jdbc.query(
                "select id, org_id from users",
                (rs, rowNum) -> new long[]{rs.getLong("id"), rs.getLong("org_id")});

        int assigned = 0;
        for (long[] uo : userOrgs) {
            int nRoles = faker.number().numberBetween(1, 3);
            List<Long> shuffled = new ArrayList<>(roleIds);
            java.util.Collections.shuffle(shuffled, faker.random().getRandomInternal());
            for (int r = 0; r < nRoles && r < shuffled.size(); r++) {
                jdbc.update(
                        "insert into role_assignments (user_id, role_id, org_id, assigned_at) " +
                        "values (?, ?, ?, ?) on conflict do nothing",
                        uo[0], shuffled.get(r), uo[1], instantInPast(365));
                assigned++;
            }
        }
        log.info("  role_assignments: {}", assigned);
    }

    private List<Long> seedSubscriptions(List<Long> orgIds, List<Long> planIds) {
        String[] statuses = {"active", "active", "active", "trial", "paused", "cancelled"};
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < N_SUBSCRIPTIONS; i++) {
            String status = statuses[faker.number().numberBetween(0, statuses.length)];
            OffsetDateTime started = instantInPast(365 * 2);
            OffsetDateTime cancelled = "cancelled".equals(status)
                    ? started.plus(faker.number().numberBetween(30, 600), ChronoUnit.DAYS)
                    : null;
            ids.add(jdbc.queryForObject(
                    "insert into subscriptions (org_id, plan_id, status, started_at, cancelled_at) " +
                    "values (?, ?, ?, ?, ?) returning id",
                    Long.class,
                    orgIds.get(faker.number().numberBetween(0, orgIds.size())),
                    planIds.get(faker.number().numberBetween(0, planIds.size())),
                    status, started, cancelled));
        }
        log.info("  subscriptions: {}", ids.size());
        return ids;
    }

    private List<Long> seedInvoices(List<Long> orgIds, List<Long> subscriptionIds) {
        // Pull each subscription's org so invoices stay tenant-consistent.
        List<long[]> subOrgs = jdbc.query(
                "select id, org_id from subscriptions",
                (rs, rowNum) -> new long[]{rs.getLong("id"), rs.getLong("org_id")});

        String[] statuses = {"paid", "paid", "paid", "sent", "overdue", "void"};
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < N_INVOICES; i++) {
            long[] subOrg = subOrgs.get(faker.number().numberBetween(0, subOrgs.size()));
            OffsetDateTime issued = instantInPast(365);
            BigDecimal total = BigDecimal.valueOf(faker.number().numberBetween(2000, 250_000))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            String status = statuses[faker.number().numberBetween(0, statuses.length)];
            ids.add(jdbc.queryForObject(
                    "insert into invoices (org_id, subscription_id, invoice_number, status, amount_total, " +
                    "currency, issued_at, due_at, paid_at) " +
                    "values (?, ?, ?, ?, ?, 'USD', ?, ?, ?) returning id",
                    Long.class,
                    subOrg[1], subOrg[0],
                    "INV-" + String.format("%06d", 10_000 + i),
                    status, total,
                    issued,
                    issued.plus(30, ChronoUnit.DAYS),
                    "paid".equals(status) ? issued.plus(faker.number().numberBetween(1, 35), ChronoUnit.DAYS) : null));
        }
        log.info("  invoices: {}", ids.size());
        return ids;
    }

    private void seedInvoiceLineItems(List<Long> invoiceIds) {
        int total = 0;
        for (Long invoiceId : invoiceIds) {
            int n = 1 + faker.number().numberBetween(0, N_LINE_ITEMS_PER_INVOICE_MAX);
            for (int i = 0; i < n; i++) {
                BigDecimal qty = BigDecimal.valueOf(faker.number().numberBetween(1, 50));
                BigDecimal unit = BigDecimal.valueOf(faker.number().numberBetween(1000, 20_000))
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                BigDecimal amount = qty.multiply(unit);
                jdbc.update(
                        "insert into invoice_line_items (invoice_id, description, quantity, unit_price, amount) " +
                        "values (?, ?, ?, ?, ?)",
                        invoiceId,
                        faker.commerce().productName(),
                        qty, unit, amount);
                total++;
            }
        }
        log.info("  invoice_line_items: {}", total);
    }

    private void seedPayments(List<Long> invoiceIds) {
        String[] methods = {"card", "card", "card", "ach", "wire"};
        int total = 0;
        for (Long invoiceId : invoiceIds) {
            if (faker.random().nextDouble() < PAYMENT_RATE) {
                OffsetDateTime received = instantInPast(300);
                BigDecimal amount = BigDecimal.valueOf(faker.number().numberBetween(2000, 250_000))
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                jdbc.update(
                        "insert into payments (invoice_id, amount, currency, method, status, received_at) " +
                        "values (?, ?, 'USD', ?, ?, ?)",
                        invoiceId, amount,
                        methods[faker.number().numberBetween(0, methods.length)],
                        faker.random().nextDouble() < 0.95 ? "succeeded" : "failed",
                        received);
                total++;
            }
        }
        log.info("  payments: {}", total);
    }

    private void seedSessions(List<Long> userIds) {
        for (int i = 0; i < N_SESSIONS; i++) {
            OffsetDateTime started = instantInPast(180);
            OffsetDateTime ended = started.plus(faker.number().numberBetween(30, 7200), ChronoUnit.SECONDS);
            jdbc.update(
                    "insert into sessions (user_id, started_at, ended_at, ip_address, user_agent) " +
                    "values (?, ?, ?, ?::inet, ?)",
                    userIds.get(faker.number().numberBetween(0, userIds.size())),
                    started, ended,
                    faker.internet().ipV4Address(),
                    faker.internet().userAgent());
        }
        log.info("  sessions: {}", N_SESSIONS);
    }

    private void seedUsageEvents(List<Long> orgIds, List<Long> userIds, List<Long> productIds) {
        String[] eventTypes = {"view", "edit", "export", "invite", "comment", "share"};
        // Need user→org pairs for tenant consistency.
        List<long[]> userOrgs = jdbc.query(
                "select id, org_id from users",
                (rs, rowNum) -> new long[]{rs.getLong("id"), rs.getLong("org_id")});

        for (int i = 0; i < N_USAGE_EVENTS; i++) {
            long[] uo = userOrgs.get(faker.number().numberBetween(0, userOrgs.size()));
            jdbc.update(
                    "insert into usage_events (org_id, user_id, product_id, event_type, occurred_at, metadata) " +
                    "values (?, ?, ?, ?, ?, ?::jsonb)",
                    uo[1], uo[0],
                    productIds.get(faker.number().numberBetween(0, productIds.size())),
                    eventTypes[faker.number().numberBetween(0, eventTypes.length)],
                    instantInPast(180),
                    "{\"client\":\"" + faker.app().name() + "\"}");
        }
        log.info("  usage_events: {}", N_USAGE_EVENTS);
    }

    private void seedSupportTickets(List<Long> orgIds, List<Long> userIds) {
        String[] priorities = {"low", "normal", "normal", "high", "urgent"};
        String[] statuses = {"open", "in_progress", "waiting", "resolved", "resolved", "closed"};
        List<long[]> userOrgs = jdbc.query(
                "select id, org_id from users",
                (rs, rowNum) -> new long[]{rs.getLong("id"), rs.getLong("org_id")});
        for (int i = 0; i < N_TICKETS; i++) {
            long[] uo = userOrgs.get(faker.number().numberBetween(0, userOrgs.size()));
            String status = statuses[faker.number().numberBetween(0, statuses.length)];
            OffsetDateTime opened = instantInPast(180);
            jdbc.update(
                    "insert into support_tickets (org_id, user_id, subject, description, priority, status, opened_at, resolved_at) " +
                    "values (?, ?, ?, ?, ?, ?, ?, ?)",
                    uo[1], uo[0],
                    faker.lorem().sentence(),
                    faker.lorem().paragraph(),
                    priorities[faker.number().numberBetween(0, priorities.length)],
                    status, opened,
                    ("resolved".equals(status) || "closed".equals(status))
                            ? opened.plus(faker.number().numberBetween(1, 240), ChronoUnit.HOURS)
                            : null);
        }
        log.info("  support_tickets: {}", N_TICKETS);
    }

    private void seedChurnSignals(List<Long> orgIds) {
        String[] types = {"usage_drop", "support_volume", "payment_failure", "cancel_intent"};
        String[] severities = {"low", "medium", "medium", "high"};
        for (int i = 0; i < N_CHURN_SIGNALS; i++) {
            OffsetDateTime detected = instantInPast(120);
            boolean resolved = faker.random().nextBoolean();
            jdbc.update(
                    "insert into churn_signals (org_id, signal_type, severity, detected_at, resolved_at) " +
                    "values (?, ?, ?, ?, ?)",
                    orgIds.get(faker.number().numberBetween(0, orgIds.size())),
                    types[faker.number().numberBetween(0, types.length)],
                    severities[faker.number().numberBetween(0, severities.length)],
                    detected,
                    resolved ? detected.plus(faker.number().numberBetween(1, 30), ChronoUnit.DAYS) : null);
        }
        log.info("  churn_signals: {}", N_CHURN_SIGNALS);
    }

    private void seedStripeCustomers(List<Long> userIds) {
        for (Long userId : userIds) {
            jdbc.update(
                    "insert into stripe_customers (user_id, stripe_customer_id, default_payment_method, created_at) " +
                    "values (?, ?, ?, ?)",
                    userId,
                    "cus_" + faker.regexify("[A-Za-z0-9]{14}"),
                    "pm_" + faker.regexify("[A-Za-z0-9]{20}"),
                    instantInPast(720));
        }
        log.info("  stripe_customers: {}", userIds.size());
    }

    private void seedMonthlyRecurringRevenue(List<Long> orgIds) {
        LocalDate monthStart = LocalDate.now().withDayOfMonth(1).minusMonths(N_MRR_MONTHS - 1);
        int total = 0;
        for (Long orgId : orgIds) {
            LocalDate m = monthStart;
            BigDecimal running = BigDecimal.valueOf(faker.number().numberBetween(5000, 50_000));
            for (int i = 0; i < N_MRR_MONTHS; i++) {
                BigDecimal newMrr = bd(faker.number().numberBetween(0, 4000));
                BigDecimal expansion = bd(faker.number().numberBetween(0, 2000));
                BigDecimal contraction = bd(faker.number().numberBetween(0, 1500));
                BigDecimal churned = bd(faker.number().numberBetween(0, 2000));
                running = running.add(newMrr).add(expansion).subtract(contraction).subtract(churned);
                if (running.compareTo(BigDecimal.ZERO) < 0) running = BigDecimal.ZERO;
                jdbc.update(
                        "insert into monthly_recurring_revenue " +
                        "(org_id, month, mrr_usd, new_mrr, expansion_mrr, contraction_mrr, churned_mrr) " +
                        "values (?, ?, ?, ?, ?, ?, ?)",
                        orgId, m, running, newMrr, expansion, contraction, churned);
                m = m.plusMonths(1);
                total++;
            }
        }
        log.info("  monthly_recurring_revenue: {}", total);
    }

    private void seedFeatureAdoptionRollups(List<Long> orgIds, List<Long> productIds) {
        LocalDate weekStart = LocalDate.now()
                .with(java.time.DayOfWeek.MONDAY)
                .minusWeeks(N_ADOPTION_WEEKS - 1);
        int total = 0;
        for (Long orgId : orgIds) {
            for (Long productId : productIds) {
                LocalDate w = weekStart;
                for (int i = 0; i < N_ADOPTION_WEEKS; i++) {
                    int distinctUsers = faker.number().numberBetween(0, 25);
                    int totalEvents = distinctUsers * faker.number().numberBetween(1, 20);
                    jdbc.update(
                            "insert into feature_adoption_rollups " +
                            "(org_id, product_id, week, distinct_users, total_events) " +
                            "values (?, ?, ?, ?, ?)",
                            orgId, productId, w, distinctUsers, totalEvents);
                    w = w.plusWeeks(1);
                    total++;
                }
            }
        }
        log.info("  feature_adoption_rollups: {}", total);
    }

    private void seedChurnCohortFacts() {
        LocalDate cohortStart = LocalDate.now().withDayOfMonth(1).minusMonths(11);
        int total = 0;
        for (int c = 0; c < 12; c++) {
            LocalDate cohort = cohortStart.plusMonths(c);
            int starting = faker.number().numberBetween(8, 25);
            double retention = 1.0;
            for (int m = 0; m < 12; m++) {
                int remaining = (int) Math.round(starting * retention);
                jdbc.update(
                        "insert into churn_cohort_facts " +
                        "(cohort_month, months_since_join, orgs_remaining, retention_pct) " +
                        "values (?, ?, ?, ?)",
                        cohort, m, remaining,
                        bd((int) (retention * 10_000)).divide(bd(100), 2, RoundingMode.HALF_UP));
                retention *= (1.0 - faker.number().randomDouble(3, 1, 80) / 1000.0);
                total++;
            }
        }
        log.info("  churn_cohort_facts: {}", total);
    }

    private void seedApiAuditLog(List<Long> userIds, List<Long> orgIds) {
        String[] methods = {"GET", "GET", "GET", "POST", "POST", "PUT", "DELETE"};
        String[] paths = {
                "/api/v1/orgs", "/api/v1/users", "/api/v1/invoices",
                "/api/v1/subscriptions", "/api/v1/usage",
                "/api/v1/auth/login", "/api/v1/auth/logout",
                "/api/v1/reports/export", "/api/v1/billing/upcoming"
        };
        for (int i = 0; i < N_AUDIT_ROWS; i++) {
            boolean authed = faker.random().nextDouble() < 0.92;
            jdbc.update(
                    "insert into api_audit_log " +
                    "(user_id, org_id, request_id, method, path, status_code, duration_ms, ip_address, requested_at) " +
                    "values (?, ?, ?, ?, ?, ?, ?, ?::inet, ?)",
                    authed ? userIds.get(faker.number().numberBetween(0, userIds.size())) : null,
                    authed ? orgIds.get(faker.number().numberBetween(0, orgIds.size())) : null,
                    UUID.randomUUID(),
                    methods[faker.number().numberBetween(0, methods.length)],
                    paths[faker.number().numberBetween(0, paths.length)],
                    statusCode(authed),
                    faker.number().numberBetween(5, 1500),
                    faker.internet().ipV4Address(),
                    instantInPast(60));
        }
        log.info("  api_audit_log: {}", N_AUDIT_ROWS);
    }

    private int statusCode(boolean authed) {
        if (!authed) return faker.random().nextDouble() < 0.6 ? 401 : 403;
        double r = faker.random().nextDouble();
        if (r < 0.85) return 200;
        if (r < 0.92) return 201;
        if (r < 0.96) return 400;
        if (r < 0.98) return 404;
        return 500;
    }

    private OffsetDateTime instantInPast(int maxDaysAgo) {
        long secondsAgo = (long) faker.number().numberBetween(60, maxDaysAgo * 86400);
        return OffsetDateTime.now(ZoneOffset.UTC).minus(secondsAgo, ChronoUnit.SECONDS);
    }

    private static BigDecimal bd(int v) {
        return BigDecimal.valueOf(v);
    }
}

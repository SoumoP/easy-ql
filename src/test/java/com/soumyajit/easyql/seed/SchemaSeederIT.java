package com.soumyajit.easyql.seed;

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

/**
 * Boots the application with both `test` and `seed` profiles active.
 * Spring Boot invokes the seeder as a CommandLineRunner during startup,
 * so by the time the @Test methods run, all 20 tables are populated.
 */
@SpringBootTest
@ActiveProfiles({"test", "seed"})
@Testcontainers
class SchemaSeederIT {

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

    @Autowired
    SchemaSeeder seeder;

    @Test
    void seederPopulatesAllTwentyTables() {
        assertCount("orgs",                       20);
        assertCount("plans",                       4);
        assertCount("roles",                       4);
        assertCount("products",                    5);
        assertCount("deprecated_v1_users",        80);
        assertCount("users",                     200);
        assertCount("stripe_customers",          200);
        assertCount("subscriptions",              25);
        assertCount("invoices",                  500);
        assertCount("sessions",                5_000);
        assertCount("usage_events",           20_000);
        assertCount("support_tickets",           300);
        assertCount("churn_signals",              80);
        assertCount("monthly_recurring_revenue", 20 * 12);
        assertCount("feature_adoption_rollups",  20 * 5 * 12);
        assertCount("churn_cohort_facts",        12 * 12);
        assertCount("api_audit_log",         10_000);

        // Variable-count tables (derived) — assert lower bounds.
        assertCountAtLeast("role_assignments",   200);   // at least 1 per user
        assertCountAtLeast("invoice_line_items", 500);   // at least 1 per invoice
        assertCountAtLeast("payments",           400);   // ~90% of invoices paid
    }

    @Test
    void noOrphanRowsInTenantTables() {
        // Every row referencing org_id has a real org.
        assertNoOrphans("users", "org_id", "orgs");
        assertNoOrphans("subscriptions", "org_id", "orgs");
        assertNoOrphans("invoices", "org_id", "orgs");
        assertNoOrphans("usage_events", "org_id", "orgs");
        assertNoOrphans("support_tickets", "org_id", "orgs");

        // Cross-system: every stripe_customers.user_id is a real user.
        assertNoOrphans("stripe_customers", "user_id", "users");

        // Invoice line items point at real invoices.
        assertNoOrphans("invoice_line_items", "invoice_id", "invoices");
    }

    @Test
    void tenantConsistencyInvoiceMatchesSubscriptionOrg() {
        // For every invoice, the linked subscription's org_id must match.
        Integer mismatches = jdbc.queryForObject(
                "select count(*) from invoices i " +
                "join subscriptions s on s.id = i.subscription_id " +
                "where s.org_id <> i.org_id",
                Integer.class);
        assertThat(mismatches).isZero();
    }

    @Test
    void seederIsIdempotent() {
        long usersBefore = jdbc.queryForObject("select count(*) from users", Long.class);
        long usageBefore = jdbc.queryForObject("select count(*) from usage_events", Long.class);

        seeder.run();

        long usersAfter = jdbc.queryForObject("select count(*) from users", Long.class);
        long usageAfter = jdbc.queryForObject("select count(*) from usage_events", Long.class);

        // Same counts because the seeder TRUNCATEs and reseeds with a fixed Faker seed.
        assertThat(usersAfter).isEqualTo(usersBefore);
        assertThat(usageAfter).isEqualTo(usageBefore);
    }

    private void assertCount(String table, long expected) {
        Long actual = jdbc.queryForObject("select count(*) from " + table, Long.class);
        assertThat(actual).as("row count for %s", table).isEqualTo(expected);
    }

    private void assertCountAtLeast(String table, long minimum) {
        Long actual = jdbc.queryForObject("select count(*) from " + table, Long.class);
        assertThat(actual).as("row count for %s", table).isGreaterThanOrEqualTo(minimum);
    }

    private void assertNoOrphans(String childTable, String fkColumn, String parentTable) {
        Long orphans = jdbc.queryForObject(
                "select count(*) from " + childTable + " c " +
                "where c." + fkColumn + " is not null " +
                "and not exists (select 1 from " + parentTable + " p where p.id = c." + fkColumn + ")",
                Long.class);
        assertThat(orphans).as("orphan rows in %s.%s -> %s", childTable, fkColumn, parentTable).isZero();
    }
}

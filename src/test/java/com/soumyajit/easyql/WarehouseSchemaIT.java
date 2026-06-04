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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the V2 warehouse schema migration applies cleanly and that the
 * deliberate retrieval-pressure warts encoded in the schema are present.
 * Each assertion below corresponds to a numbered wart in the V2 migration header.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class WarehouseSchemaIT {

    private static final Set<String> EXPECTED_TABLES = Set.of(
            "orgs", "plans", "roles", "products", "deprecated_v1_users",
            "users", "role_assignments",
            "subscriptions", "invoices", "invoice_line_items", "payments",
            "sessions", "usage_events",
            "support_tickets", "churn_signals",
            "stripe_customers",
            "monthly_recurring_revenue", "feature_adoption_rollups", "churn_cohort_facts",
            "api_audit_log"
    );

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
    void allTwentyWarehouseTablesExist() {
        List<String> actual = jdbc.queryForList(
                "select table_name from information_schema.tables " +
                "where table_schema = 'public' " +
                "and table_name not in ('flyway_schema_history', '_easyql_meta') " +
                "order by table_name",
                String.class);

        assertThat(actual).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
        assertThat(actual).hasSize(20);
    }

    /** WART #1: same domain (users), divergent column names — retrieval target. */
    @Test
    void wart1_usersAndDeprecatedV1UsersHaveDivergentColumnNames() {
        List<String> usersColumns = columnsOf("users");
        List<String> deprecatedColumns = columnsOf("deprecated_v1_users");

        assertThat(usersColumns).contains("email", "name", "created_at");
        assertThat(usersColumns).doesNotContain("email_addr", "full_name", "joined_dt");

        assertThat(deprecatedColumns).contains("email_addr", "full_name", "joined_dt");
        assertThat(deprecatedColumns).doesNotContain("email", "name", "created_at");
    }

    /** WART #2: redundant fields (users.status text vs users.is_active boolean). */
    @Test
    void wart2_usersHasBothStatusAndIsActive() {
        List<String> columns = columnsOf("users");
        assertThat(columns).contains("status", "is_active");
    }

    /** WART #3: three raw/rollup pairs both present (worker must pick the right path). */
    @Test
    void wart3_rawAndRollupTablesBothExist() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables " +
                "where table_schema = 'public' and table_name in (" +
                "'invoices', 'payments', 'monthly_recurring_revenue', " +
                "'usage_events', 'feature_adoption_rollups', " +
                "'churn_signals', 'churn_cohort_facts')",
                String.class);

        assertThat(tables).containsExactlyInAnyOrder(
                "invoices", "payments", "monthly_recurring_revenue",
                "usage_events", "feature_adoption_rollups",
                "churn_signals", "churn_cohort_facts"
        );
    }

    /** WART #4: role_assignments forces a 3-way join with org-scoping. */
    @Test
    void wart4_roleAssignmentsHasThreeForeignKeys() {
        List<String> fkColumns = jdbc.queryForList(
                "select kcu.column_name " +
                "from information_schema.table_constraints tc " +
                "join information_schema.key_column_usage kcu " +
                "  on tc.constraint_name = kcu.constraint_name " +
                "  and tc.table_schema = kcu.table_schema " +
                "where tc.table_schema = 'public' " +
                "  and tc.table_name = 'role_assignments' " +
                "  and tc.constraint_type = 'FOREIGN KEY' " +
                "order by kcu.column_name",
                String.class);

        assertThat(fkColumns).containsExactlyInAnyOrder("user_id", "role_id", "org_id");
    }

    /** WART #5: org_id cascades into every tenant-scoped table. */
    @Test
    void wart5_orgIdCascadesIntoTenantTables() {
        List<String> expectOrgFkOn = List.of(
                "users", "role_assignments", "subscriptions", "invoices",
                "usage_events", "support_tickets", "churn_signals",
                "monthly_recurring_revenue", "feature_adoption_rollups"
        );

        for (String table : expectOrgFkOn) {
            List<String> orgFks = jdbc.queryForList(
                    "select kcu.column_name " +
                    "from information_schema.table_constraints tc " +
                    "join information_schema.key_column_usage kcu " +
                    "  on tc.constraint_name = kcu.constraint_name " +
                    "  and tc.table_schema = kcu.table_schema " +
                    "join information_schema.constraint_column_usage ccu " +
                    "  on ccu.constraint_name = tc.constraint_name " +
                    "  and ccu.table_schema = tc.table_schema " +
                    "where tc.table_schema = 'public' " +
                    "  and tc.table_name = ? " +
                    "  and tc.constraint_type = 'FOREIGN KEY' " +
                    "  and ccu.table_name = 'orgs'",
                    String.class,
                    table);

            assertThat(orgFks)
                    .as("table %s should have an FK to orgs", table)
                    .contains("org_id");
        }
    }

    /** WART #6: stripe_customers introduces a different ID space (cross-system). */
    @Test
    void wart6_stripeCustomersHasDifferentIdSpace() {
        List<String> columns = columnsOf("stripe_customers");
        assertThat(columns).contains("stripe_customer_id", "user_id");

        Integer uniqueOnStripeId = jdbc.queryForObject(
                "select count(*) from information_schema.table_constraints " +
                "where table_schema = 'public' " +
                "  and table_name = 'stripe_customers' " +
                "  and constraint_type = 'UNIQUE'",
                Integer.class);
        assertThat(uniqueOnStripeId).isGreaterThanOrEqualTo(1);
    }

    @Test
    void v2MigrationRecordedInMetaTable() {
        String warehouseVersion = jdbc.queryForObject(
                "select value from _easyql_meta where key = 'schema_version_warehouse'",
                String.class);
        assertThat(warehouseVersion).isEqualTo("V2");

        String tableCount = jdbc.queryForObject(
                "select value from _easyql_meta where key = 'warehouse_table_count'",
                String.class);
        assertThat(tableCount).isEqualTo("20");
    }

    private List<String> columnsOf(String tableName) {
        return jdbc.queryForList(
                "select column_name from information_schema.columns " +
                "where table_schema = 'public' and table_name = ? " +
                "order by column_name",
                String.class,
                tableName);
    }
}

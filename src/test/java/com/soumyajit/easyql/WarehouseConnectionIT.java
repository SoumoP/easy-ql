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
        // V1 baseline must always have inserted these specific rows.
        // We don't count total rows because later migrations add their own meta entries.
        String projectName = jdbc.queryForObject(
                "select value from _easyql_meta where key = 'project'",
                String.class);
        assertThat(projectName).isEqualTo("easy-ql");

        String baselineVersion = jdbc.queryForObject(
                "select value from _easyql_meta where key = 'schema_version_baseline'",
                String.class);
        assertThat(baselineVersion).isEqualTo("V1");

        String planOrigin = jdbc.queryForObject(
                "select value from _easyql_meta where key = 'plan_origin'",
                String.class);
        assertThat(planOrigin).isEqualTo("plan-1-foundation");
    }
}

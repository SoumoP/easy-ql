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

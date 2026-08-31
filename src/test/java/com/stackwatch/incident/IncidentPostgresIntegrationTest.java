package com.stackwatch.incident;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class IncidentPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void migratesTheIncidentAuditSchema() throws Exception {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas("stackwatch_incident")
            .createSchemas(true)
            .load()
            .migrate();

        try (var connection = postgres.createConnection("");
             var statement = connection.prepareStatement("SELECT to_regclass(?)")) {
            for (String table : new String[] {
                "incidents", "triggers", "steps", "observations", "evidence", "hypotheses", "reports"
            }) {
                statement.setString(1, "stackwatch_incident." + table);
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("stackwatch_incident." + table);
                }
            }
        }
    }
}

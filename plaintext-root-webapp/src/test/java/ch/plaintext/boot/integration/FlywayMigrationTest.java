/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test verifying that all Flyway migrations run successfully
 * against a real PostgreSQL database (embedded, see EmbeddedPg).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class FlywayMigrationTest {


    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "flywaymigrationtest");
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Test
    void allMigrationsRunSuccessfully() {
        MigrationInfo[] applied = flyway.info().applied();
        assertNotNull(applied);
        assertTrue(applied.length > 0, "At least one migration should have been applied");

        for (MigrationInfo info : applied) {
            assertNull(info.getState().isResolved() ? null : info.getState(),
                    "Migration " + info.getVersion() + " should be resolved, but was: " + info.getState());
        }
    }

    @Test
    void noMigrationsPending() {
        MigrationInfo[] pending = flyway.info().pending();
        assertEquals(0, pending.length, "No migrations should be pending after startup");
    }

    @Test
    void coreTablesExist() throws Exception {
        List<String> expectedTables = List.of(
                "my_user_entity",
                "persistent_logins",
                "simple_storable_entity"
        );

        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            List<String> actualTables = new ArrayList<>();
            try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    actualTables.add(rs.getString("TABLE_NAME").toLowerCase());
                }
            }

            for (String table : expectedTables) {
                assertTrue(actualTables.contains(table),
                        "Expected table '" + table + "' not found. Existing tables: " + actualTables);
            }
        }
    }
}

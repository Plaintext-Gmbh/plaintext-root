/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.claude.service;

import ch.plaintext.testsupport.EmbeddedPg;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Upsert and read against a real PostgreSQL, schema built from the migration itself. */
class ClaudeNutzungSpeicherTest {

    private static ClaudeNutzungSpeicher speicher;

    @BeforeAll
    static void datenbank() throws Exception {
        Map<String, Object> p = new HashMap<>();
        EmbeddedPg.registrieren((name, wert) -> p.put(name, wert.get()), "claude_nutzung_test");
        DriverManagerDataSource ds = new DriverManagerDataSource((String) p.get("spring.datasource.url"),
                (String) p.get("spring.datasource.username"), (String) p.get("spring.datasource.password"));
        try (var dateien = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path f : dateien.sorted().toList()) {
                new JdbcTemplate(ds).execute(Files.readString(f));
            }
        }
        speicher = new ClaudeNutzungSpeicher(ds);
    }

    @Test
    void zweiAufrufeWerdenZuEinerZeileAddiert() {
        speicher.erfasse("app", "sonnet", false, 10, 2, 1, 100);
        speicher.erfasse("app", "sonnet", true, 0, 0, 0, 50);
        speicher.erfasse("guild", "sonnet", false, 5, 1, 0, 30);

        List<ClaudeNutzung.Zeile> z = speicher.ladeAb(LocalDate.now());

        assertEquals(2, z.size());
        ClaudeNutzung.Zeile app = z.stream().filter(r -> r.quelle().equals("app")).findFirst().orElseThrow();
        assertEquals(new ClaudeNutzung.Zeile(LocalDate.now(), "app", "sonnet", 2, 1, 10, 2, 1, 150), app);
        assertTrue(speicher.ladeAb(LocalDate.now().plusDays(1)).isEmpty());
    }

    @Test
    void kaputteDatenbankBrichtDenAufruferNicht() {
        new ClaudeNutzungSpeicher(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/weg"))
                .erfasse("app", "sonnet", false, 1, 1, 0, 1);
    }
}

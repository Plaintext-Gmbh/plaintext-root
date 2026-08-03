/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Locale;

/**
 * Eingebettetes PostgreSQL für die Integrationstests — Ersatz für Testcontainers.
 *
 * <p><b>Warum (Karte 451, Entscheidung Daniels vom 02.08.2026):</b> Die Testklassen starteten je
 * einen {@code PostgreSQLContainer} und brauchten dafür den Docker-Daemon des Runners. Genau
 * dieser Zugang — {@code /var/run/docker.sock} im GitHub-Runner-Container, gemountet als root —
 * ist die offene Lücke: Wer einen Job ausführen darf, mountet darüber jedes Verzeichnis des Hosts.
 * Ein eingebettetes PostgreSQL läuft als normaler Prozess im Testlauf und braucht keinen Daemon.
 *
 * <p><b>Ein Server, eine Datenbank je Testklasse.</b> Der Server startet einmal pro JVM; jede
 * Testklasse bekommt über {@link #registrieren} ihre eigene, frisch angelegte Datenbank. Damit
 * bleibt die Isolation erhalten, die vorher aus „ein Container je Klasse" kam — ohne deren Kosten.
 *
 * <p><b>Bewusst in Kauf genommen:</b> Die eingebetteten Binaries stehen auf PostgreSQL 17, die
 * CI-Datenbank lief auf 18. Daniel am 02.08.2026: „mit dem risiko, dass die db nicht 1:1 ist kann
 * ich leben". Weicht ein Test wegen eines Versionsunterschieds ab, gehört das in die Karte — nicht
 * stillschweigend weggeräumt.
 */
public final class EmbeddedPg {

    /**
     * Reicht die CI eine Datenbank herein, wird DIESE benutzt statt eines eigenen Servers.
     *
     * <p><b>Warum (gemessen am 02.08.2026, PR #22):</b> Die GitHub-Runner laufen als <b>root</b>
     * — und {@code initdb} verweigert den Dienst als root
     * ({@code Process [/tmp/embedded-pg/.../initdb …] failed}). Ein eingebetteter Server ist dort
     * also gar nicht startbar, solange {@code User=root} gilt (genau der zweite Punkt der
     * Karte 451). Die Pipeline stellt ohnehin schon eine Datenbank bereit und reicht sie als
     * {@code SPRING_DATASOURCE_URL} herein; die wird hier genutzt.
     *
     * <p>Lokal — ohne diese Variable — startet der eingebettete Server wie bisher. Beide Wege
     * brauchen <b>keinen Docker-Daemon im Testprozess</b>, und darum geht es in Karte 451.
     */
    private static final String EXTERNE_URL = umgebung("SPRING_DATASOURCE_URL");
    private static final String EXTERNER_USER = wertOder(umgebung("SPRING_DATASOURCE_USERNAME"), "plaintext");
    private static final String EXTERNES_PW = wertOder(umgebung("SPRING_DATASOURCE_PASSWORD"), "plaintext");

    private static EmbeddedPostgres pg;

    private EmbeddedPg() {
    }

    private static String umgebung(String name) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static String wertOder(String wert, String ersatz) {
        return wert != null ? wert : ersatz;
    }

    private static synchronized EmbeddedPostgres server() {
        if (pg == null) {
            try {
                pg = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Eingebettetes PostgreSQL liess sich nicht starten — ohne DB ist der Test "
                                + "nicht aussagekraeftig, deshalb Abbruch statt Ueberspringen. "
                                + "Laeuft der Prozess als root? initdb verweigert das; dann gehoert "
                                + "SPRING_DATASOURCE_URL gesetzt (siehe Klassenkommentar).", e);
            }
        }
        return pg;
    }

    /**
     * Legt eine frische Datenbank für diese Testklasse an und trägt die Verbindungsdaten ein.
     *
     * @param registry Spring-Registry aus der {@code @DynamicPropertySource}-Methode
     * @param datenbank Name der Testdatenbank, üblicherweise der Klassenname in Kleinbuchstaben
     */
    public static void registrieren(DynamicPropertyRegistry registry, String datenbank) {
        final String db = datenbank.toLowerCase(Locale.ROOT);
        final String url;
        final String user;
        final String pw;
        if (EXTERNE_URL != null) {
            // Eigene Datenbank auf dem gereichten Server — damit bleibt die Isolation je
            // Testklasse erhalten, die vorher aus "ein Container je Klasse" kam.
            anlegen(EXTERNE_URL, EXTERNER_USER, EXTERNES_PW, db);
            url = EXTERNE_URL.replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1");
            user = EXTERNER_USER;
            pw = EXTERNES_PW;
        } else {
            anlegen(server().getJdbcUrl("postgres", "postgres"), "postgres", "postgres", db);
            url = server().getJdbcUrl("postgres", db);
            user = "postgres";
            pw = "postgres";
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> pw);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    private static void anlegen(String verwaltungsUrl, String user, String pw, String db) {
        try (Connection c = DriverManager.getConnection(verwaltungsUrl, user, pw);
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS \"" + db + "\"");
            s.execute("CREATE DATABASE \"" + db + "\"");
        } catch (Exception e) {
            throw new IllegalStateException("Testdatenbank '" + db + "' liess sich nicht anlegen", e);
        }
    }
}

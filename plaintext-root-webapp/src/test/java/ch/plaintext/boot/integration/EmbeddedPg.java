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
 * Embedded PostgreSQL for the integration tests — a replacement for Testcontainers.
 *
 * <p><b>Why (card 451, Daniel's decision of 02.08.2026):</b> the test classes each started
 * a {@code PostgreSQLContainer} and needed the runner's Docker daemon for it. Exactly
 * that access — {@code /var/run/docker.sock} in the GitHub runner container, mounted as root —
 * is the open gap: whoever may run a job can mount any directory of the host through it.
 * An embedded PostgreSQL runs as an ordinary process within the test run and needs no daemon.
 *
 * <p><b>One server, one database per test class.</b> The server starts once per JVM; every
 * test class gets its own, freshly created database via {@link #registrieren}. That
 * preserves the isolation that previously came from "one container per class" — without its cost.
 *
 * <p><b>Deliberately accepted:</b> the embedded binaries are on PostgreSQL 17, the
 * CI database ran on 18. Daniel on 02.08.2026: "I can live with the risk that the db is not
 * 1:1." If a test deviates because of a version difference, that belongs in the card — not
 * to be tidied away silently.
 */
public final class EmbeddedPg {

    /**
     * If the CI hands in a database, THAT one is used instead of a server of our own.
     *
     * <p><b>Why (measured on 02.08.2026, PR #22):</b> the GitHub runners run as <b>root</b>
     * — and {@code initdb} refuses to work as root
     * ({@code Process [/tmp/embedded-pg/.../initdb …] failed}). An embedded server therefore
     * cannot be started there at all as long as {@code User=root} holds (exactly the second point of
     * card 451). The pipeline already provides a database anyway and hands it in as
     * {@code SPRING_DATASOURCE_URL}; that is what is used here.
     *
     * <p>Locally — without this variable — the embedded server starts as before. Both ways
     * need <b>no Docker daemon in the test process</b>, and that is what card 451 is about.
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
     * Creates a fresh database for this test class and registers the connection details.
     *
     * @param registry Spring registry from the {@code @DynamicPropertySource} method
     * @param datenbank name of the test database, usually the class name in lower case
     */
    public static void registrieren(DynamicPropertyRegistry registry, String datenbank) {
        final String db = datenbank.toLowerCase(Locale.ROOT);
        final String url;
        final String user;
        final String pw;
        if (EXTERNE_URL != null) {
            // Our own database on the handed-in server — this preserves the isolation per
            // test class that previously came from "one container per class".
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

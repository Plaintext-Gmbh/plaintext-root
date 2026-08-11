/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import ch.plaintext.apitoken.ApiToken;
import ch.plaintext.apitoken.ApiTokenRepository;
import ch.plaintext.apitoken.ApiTokenRevocationLookup;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vertragstest zu Karte 659: Der JDBC-Revocation-Lookup muss dieselbe Zugriffsentscheidung
 * treffen wie der bisherige JPA-Weg — und darf dabei keine DB-Verbindung am Request halten.
 *
 * <p><b>Warum als Integrationstest und nicht mit Mocks:</b> Geprüft wird gerade die Übereinstimmung
 * zweier <i>Datenbankzugriffe</i>. Ein Mock würde beide Seiten dasselbe antworten lassen und den
 * Test zur Tautologie machen. Beide Wege laufen hier gegen dieselbe echte Tabelle im selben
 * Zustand ({@link EmbeddedPg}, Karte 451 — kein Docker-Daemon nötig).
 *
 * <p><b>Der Referenzweg</b> ({@link #jpaEntscheidung}) bildet {@code validateVerifiedToken} vor dem
 * Umbau nach: {@code findByTokenHash} + {@code getDeleted()} + {@code isInvalidated()}. Er ist
 * bewusst hier nachgebaut und nicht aus dem Service aufgerufen — der Service benutzt inzwischen
 * den JDBC-Weg, ein Vergleich mit sich selbst wäre wertlos.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class ApiTokenRevocationVertragIT {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "apitokenrevocationvertragit");
    }

    @Autowired
    private ApiTokenRepository repository;

    @Autowired
    private ApiTokenRevocationLookup lookup;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /** Ergebnis einer Zugriffsentscheidung — {@code null} heisst „abgewiesen". */
    private record Entscheidung(boolean gueltig, String userEmail) {
        static final Entscheidung ABGEWIESEN = new Entscheidung(false, null);
    }

    // ── Die beiden Wege ─────────────────────────────────────────────────────────────────────────

    /** Der alte Weg über JPA, so wie {@code validateVerifiedToken} ihn bis Karte 659 ging. */
    private Entscheidung jpaEntscheidung(String hash) {
        Optional<ApiToken> gefunden = repository.findByTokenHash(hash);
        if (gefunden.isEmpty()) {
            return Entscheidung.ABGEWIESEN;
        }
        ApiToken t = gefunden.get();
        if (Boolean.TRUE.equals(t.getDeleted()) || t.isInvalidated()) {
            return Entscheidung.ABGEWIESEN;
        }
        return new Entscheidung(true, t.getUserEmail());
    }

    /** Der neue Weg über JDBC. */
    private Entscheidung jdbcEntscheidung(String hash) {
        Optional<ApiTokenRevocationLookup.TokenZustand> gefunden = lookup.findForValidation(hash);
        if (gefunden.isEmpty()) {
            return Entscheidung.ABGEWIESEN;
        }
        ApiTokenRevocationLookup.TokenZustand t = gefunden.get();
        if (t.deleted() || t.invalidated()) {
            return Entscheidung.ABGEWIESEN;
        }
        return new Entscheidung(true, t.userEmail());
    }

    // ── Der Vertrag ─────────────────────────────────────────────────────────────────────────────

    @Test
    void beideWegeEntscheidenGleich_ueberAlleKombinationen() {
        // deleted / invalidated / abgelaufen, jeweils in allen Kombinationen
        for (boolean geloescht : new boolean[]{false, true}) {
            for (boolean invalidiert : new boolean[]{false, true}) {
                for (boolean abgelaufen : new boolean[]{false, true}) {
                    String hash = anlegen("h-" + geloescht + invalidiert + abgelaufen,
                            geloescht, invalidiert, abgelaufen);
                    assertEquals(jpaEntscheidung(hash), jdbcEntscheidung(hash),
                            "Abweichung bei deleted=" + geloescht + " invalidated=" + invalidiert
                                    + " abgelaufen=" + abgelaufen);
                }
            }
        }
    }

    @Test
    void unbekannterHash_wirdVonBeidenWegenAbgewiesen() {
        String hash = "diesen-hash-gibt-es-nicht";
        assertEquals(Entscheidung.ABGEWIESEN, jpaEntscheidung(hash));
        assertEquals(Entscheidung.ABGEWIESEN, jdbcEntscheidung(hash));
    }

    /**
     * Positivkontrolle zum Test darüber: Ohne sie belegte „beide gleich" auch dann etwas, wenn
     * schlicht jeder Token abgewiesen würde. Ein sauberer Token muss durchkommen — mit Mailadresse.
     */
    @Test
    void sauberesToken_kommtBeiBeidenDurch() {
        String hash = anlegen("h-sauber", false, false, false);
        assertEquals(new Entscheidung(true, "u@x.ch"), jpaEntscheidung(hash));
        assertEquals(new Entscheidung(true, "u@x.ch"), jdbcEntscheidung(hash));
    }

    /**
     * {@code deleted} ist laut {@code V1775256894} <b>nullable</b>, und {@code SuperModel.deleted}
     * ist ein {@code Boolean}. Der alte Weg lief bei {@code NULL} in eine NullPointerException
     * (Unboxing) — also in einen 500er statt in eine Zugriffsentscheidung. Der JDBC-Weg liest
     * {@code NULL} als „nie gelöscht worden".
     *
     * <p>Das ist die einzige gewollte Verhaltensänderung des Umbaus, und sie geht in die sichere
     * Richtung: {@code deleted = true} wird weiterhin abgewiesen (Test oben).
     */
    @Test
    void deletedNull_giltAlsNichtGeloescht_undWirftNicht() {
        String hash = anlegen("h-null", false, false, false);
        new JdbcTemplate(dataSource).update("UPDATE api_token SET deleted = NULL WHERE token_hash = ?", hash);

        Entscheidung jdbc = jdbcEntscheidung(hash);
        assertTrue(jdbc.gueltig(), "deleted = NULL heisst 'nie geloescht', der Token bleibt gueltig");
        assertEquals("u@x.ch", jdbc.userEmail());
    }

    // ── Der Zweck des Umbaus, gemessen ──────────────────────────────────────────────────────────

    /**
     * Der eigentliche Grund für Karte 659: Mit gebundenem EntityManager — genau das tut
     * {@code OpenEntityManagerInViewFilter} um die ganze Filterkette herum — hält der JPA-Zugriff
     * seine Hikari-Verbindung bis zum Requestende, der JDBC-Zugriff nicht.
     *
     * <p><b>Die Reihenfolge ist der Test.</b> Zuerst der JDBC-Weg auf einer frischen Session: er
     * muss den Pool bei 0 lassen. <i>Danach</i> der JPA-Weg als Positivkontrolle — er muss auf 1
     * gehen, sonst misst diese Methode gar nichts und die 0 davor wäre wertlos. Umgekehrt gemessen
     * (erst JPA, dann JDBC) wäre der Test blind: Die JPA-Session hielte die Verbindung ohnehin
     * schon, und ein zweiter Zugriff — egal welcher Art — fiele nicht mehr auf.
     */
    @Test
    void jdbcWegHaeltKeineVerbindungAmRequest_jpaWegSchon() {
        String hash = anlegen("h-leak", false, false, false);
        HikariDataSource pool = ((HikariDataSource) dataSource);

        EntityManager em = entityManagerFactory.createEntityManager();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(em));
        try {
            assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections(), "Ausgangslage");

            jdbcEntscheidung(hash);
            int nachJdbc = pool.getHikariPoolMXBean().getActiveConnections();

            jpaEntscheidung(hash);
            int nachJpa = pool.getHikariPoolMXBean().getActiveConnections();

            assertEquals(0, nachJdbc,
                    "der JDBC-Weg gibt seine Verbindung sofort zurueck (das ist der Zweck von Karte 659)");
            assertEquals(1, nachJpa,
                    "Positivkontrolle: der JPA-Weg haelt seine Verbindung an der gebundenen Session — "
                            + "schlaegt DIESE Zeile fehl, misst der Test nichts und die 0 oben ist wertlos");
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            em.close();
        }
        assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections(),
                "nach dem Schliessen der Session ist der Pool wieder frei");
    }

    @Test
    void markUsed_zaehltHochUndSetztZeitstempel() {
        String hash = anlegen("h-used", false, false, false);
        ApiTokenRevocationLookup.TokenZustand t = lookup.findForValidation(hash).orElseThrow();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long vorher = jdbc.queryForObject("SELECT use_count FROM api_token WHERE id = ?", Long.class, t.id());

        lookup.markUsed(t.id());

        assertEquals(vorher + 1, jdbc.queryForObject(
                "SELECT use_count FROM api_token WHERE id = ?", Long.class, t.id()));
        assertNotNull(jdbc.queryForObject(
                "SELECT last_used_at FROM api_token WHERE id = ?", LocalDateTime.class, t.id()));
        assertNotNull(jdbc.queryForObject(
                "SELECT updated_at FROM api_token WHERE id = ?", LocalDateTime.class, t.id()));
    }

    /**
     * Gegenprobe zu {@link #markUsed_zaehltHochUndSetztZeitstempel()}: Die Auditspalten bleiben
     * bewusst unberührt — ein Token-<i>Gebrauch</i> ist keine fachliche Änderung, und der
     * JPA-Auditor schriebe im Filterkontext „system" über den letzten echten Bearbeiter.
     */
    @Test
    void markUsed_laesstAuditspaltenInRuhe() {
        String hash = anlegen("h-audit", false, false, false);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ApiTokenRevocationLookup.TokenZustand t = lookup.findForValidation(hash).orElseThrow();
        jdbc.update("UPDATE api_token SET last_modified_by = 'daniel' WHERE id = ?", t.id());

        lookup.markUsed(t.id());

        assertEquals("daniel", jdbc.queryForObject(
                "SELECT last_modified_by FROM api_token WHERE id = ?", String.class, t.id()));
    }

    @Test
    void leererOderNullHash_gehtGarNichtErstZurDatenbank() {
        assertFalse(lookup.findForValidation(null).isPresent());
        assertFalse(lookup.findForValidation("").isPresent());
    }

    // ── Hilfsmittel ─────────────────────────────────────────────────────────────────────────────

    /** Legt einen Token an und gibt seinen Hash zurück. */
    private String anlegen(String hash, boolean geloescht, boolean invalidiert, boolean abgelaufen) {
        repository.findByTokenHash(hash).ifPresent(repository::delete);
        ApiToken t = new ApiToken();
        t.setTokenHash(hash);
        t.setUserId(7L);
        t.setMandat("plaintext");
        t.setUserEmail("u@x.ch");
        t.setTokenName("cli");
        t.setDeleted(geloescht);
        t.setInvalidated(invalidiert);
        t.setUseCount(3L);
        t.setExpiresAt(abgelaufen
                ? LocalDateTime.now().minusDays(1)
                : LocalDateTime.now().plusDays(30));
        repository.saveAndFlush(t);
        return hash;
    }
}

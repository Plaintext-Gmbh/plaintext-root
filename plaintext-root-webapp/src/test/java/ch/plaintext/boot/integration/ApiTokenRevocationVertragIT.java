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
 * Contract test for card 659: the JDBC revocation lookup has to reach the same access decision
 * as the previous JPA path — and must not hold a DB connection for the duration of the request.
 *
 * <p><b>Why as an integration test and not with mocks:</b> what is checked is precisely the agreement
 * of two <i>database accesses</i>. A mock would make both sides answer the same and turn the
 * test into a tautology. Both paths run here against the same real table in the same
 * state ({@link EmbeddedPg}, card 451 — no Docker daemon needed).
 *
 * <p><b>The reference path</b> ({@link #jpaEntscheidung}) reproduces {@code validateVerifiedToken} as it
 * was before the rebuild: {@code findByTokenHash} + {@code getDeleted()} + {@code isInvalidated()}. It is
 * deliberately rebuilt here and not called from the service — the service meanwhile takes
 * the JDBC path, and a comparison with itself would be worthless.
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

    /** Result of an access decision — {@code null} means "rejected". */
    private record Entscheidung(boolean gueltig, String userEmail) {
        static final Entscheidung ABGEWIESEN = new Entscheidung(false, null);
    }

    // ── The two paths ───────────────────────────────────────────────────────────────────────────

    /** The old path via JPA, exactly as {@code validateVerifiedToken} went until card 659. */
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

    /** The new path via JDBC. */
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

    // ── The contract ────────────────────────────────────────────────────────────────────────────

    @Test
    void beideWegeEntscheidenGleich_ueberAlleKombinationen() {
        // deleted / invalidated / expired, each in all combinations
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
     * Positive control for the test above: without it "both the same" would also hold when
     * simply every token was rejected. A clean token has to get through — with a mail address.
     */
    @Test
    void sauberesToken_kommtBeiBeidenDurch() {
        String hash = anlegen("h-sauber", false, false, false);
        assertEquals(new Entscheidung(true, "u@x.ch"), jpaEntscheidung(hash));
        assertEquals(new Entscheidung(true, "u@x.ch"), jdbcEntscheidung(hash));
    }

    /**
     * According to {@code V1775256894} {@code deleted} is <b>nullable</b>, and {@code SuperModel.deleted}
     * is a {@code Boolean}. On {@code NULL} the old path ran into a NullPointerException
     * (unboxing) — that is, into a 500 instead of an access decision. The JDBC path reads
     * {@code NULL} as "never been deleted".
     *
     * <p>That is the only intended behavioural change of the rebuild, and it goes in the safe
     * direction: {@code deleted = true} is still rejected (test above).
     */
    @Test
    void deletedNull_giltAlsNichtGeloescht_undWirftNicht() {
        String hash = anlegen("h-null", false, false, false);
        new JdbcTemplate(dataSource).update("UPDATE api_token SET deleted = NULL WHERE token_hash = ?", hash);

        Entscheidung jdbc = jdbcEntscheidung(hash);
        assertTrue(jdbc.gueltig(), "deleted = NULL heisst 'nie geloescht', der Token bleibt gueltig");
        assertEquals("u@x.ch", jdbc.userEmail());
    }

    // ── The purpose of the rebuild, measured ────────────────────────────────────────────────────

    /**
     * The actual reason for card 659: with a bound EntityManager — exactly what
     * {@code OpenEntityManagerInViewFilter} does around the whole filter chain — the JPA access holds
     * its Hikari connection until the end of the request, the JDBC access does not.
     *
     * <p><b>The order is the test.</b> First the JDBC path on a fresh session: it
     * has to leave the pool at 0. <i>Afterwards</i> the JPA path as a positive control — it has to go
     * to 1, otherwise this method measures nothing at all and the 0 before it would be worthless.
     * Measured the other way round (JPA first, then JDBC) the test would be blind: the JPA session
     * would already be holding the connection anyway, and a second access — of whatever kind — would
     * no longer stand out.
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
     * Counter-check to {@link #markUsed_zaehltHochUndSetztZeitstempel()}: the audit columns
     * deliberately stay untouched — a <i>use</i> of a token is no substantive change, and the
     * JPA auditor would write "system" over the last real editor in the filter context.
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

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    /** Creates a token and returns its hash. */
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

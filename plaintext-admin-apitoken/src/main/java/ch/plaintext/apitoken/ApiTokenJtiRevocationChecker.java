/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) eMad, 2026.
 */
package ch.plaintext.apitoken;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Karte 664: macht {@code revoke_api_token} auch dort wirksam, wo
 * {@code plaintext.mcp.validation=JWT} gilt (app, guild, schuetu).
 *
 * <p><b>Das Problem.</b> Im JWT-Modus prüft {@link McpBearerTokenFilter} nur Signatur und Ablauf.
 * Ein widerrufenes Token funktionierte deshalb weiter — bis zu einem Jahr, bis zum JWT-Ablauf.
 * Das Werkzeug zum Stilllegen eines Tokens meldete Erfolg und tat nichts.</p>
 *
 * <p><b>Warum das nicht einfach {@code validation=DATABASE} sein darf.</b> Diese Strategie weist
 * jedes Token ab, das keine Zeile in {@code api_token} hat — und genau solche Tokens gibt es
 * legitim: Zeiterfassungs-Uhr, schuetu-Juriwagen und {@code minten} erzeugen sie direkt über
 * {@link JwtTokenService}. Ein Umstellen nähme diese Zugänge vom Netz (Karte 305).</p>
 *
 * <p><b>Der Unterschied liegt im unbekannten Token.</b> Dieser Checker sperrt nur bei einem
 * <em>positiv gefundenen, widerrufenen</em> Eintrag. Ein unbekannter jti gilt als nicht widerrufen
 * und läuft unverändert durch:</p>
 *
 * <pre>
 * validation=DATABASE   jti nicht in api_token  -&gt;  abgewiesen    (sperrt Uhr/Juriwagen/minten aus)
 * dieser Checker        jti nicht in api_token  -&gt;  durchgelassen (kein Eintrag = nicht widerrufen)
 * </pre>
 *
 * <p><b>Grenze.</b> Tokens, die vor Karte 664 ausgestellt wurden, haben keinen {@code jti} in ihrer
 * Zeile — sie bleiben bis zu ihrem Ablauf unwiderrufbar. Der jti steht nur im ausgestellten Token
 * selbst, den nur der Besitzer hat; die Datenbank kann ihn nicht nachträglich erfahren. Solche
 * Tokens müssen einmal neu ausgestellt werden.</p>
 *
 * <p><b>Kein {@code @Component}, sondern eine {@code @Bean} mit {@code @ConditionalOnMissingBean}</b>
 * ({@code ch.plaintext.apitoken.config.JtiRevocationAutoConfiguration}): plaintext-schuetu bringt
 * mit {@code RevokedTokenService} bereits eine eigene Implementierung mit. Zwei Beans desselben
 * Interface hätten {@code ObjectProvider.getIfAvailable()} in
 * {@link McpBearerTokenFilterConfig} eine {@code NoUniqueBeanDefinitionException} werfen lassen —
 * also einen Startfehler in schuetu, ausgelöst von einem Patch in root.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
public class ApiTokenJtiRevocationChecker implements JtiRevocationChecker {

    /**
     * Wie lange ein „nicht widerrufen" gilt, bevor erneut nachgesehen wird.
     *
     * <p>Der Lookup selbst ist bereits leak-frei ({@link ApiTokenRevocationLookup} geht über
     * JdbcTemplate, nicht über JPA — Karte 659). Der Cache ist deshalb keine Notlösung gegen
     * hängende Verbindungen, sondern eine schlichte Lastbremse: Eine MCP-Sitzung setzt viele
     * Requests mit <em>demselben</em> Token ab, und die Antwort ändert sich fast nie.</p>
     *
     * <p>Der Preis ist, dass ein Widerruf erst nach spätestens einer Minute greift statt sofort.
     * Gemessen an „bis zu einem Jahr" — dem Zustand vor dieser Karte — ist das der richtige
     * Tausch.</p>
     */
    static final long NEGATIV_CACHE_TTL_MS = 60_000L;

    private final ApiTokenRevocationLookup lookup;

    /** jti -&gt; Zeitpunkt (ms), bis zu dem „nicht widerrufen" ohne erneuten Lookup gilt. */
    private final Map<String, Long> nichtWiderrufenBis = new ConcurrentHashMap<>();

    public ApiTokenJtiRevocationChecker(ApiTokenRevocationLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public boolean isRevoked(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }

        long jetzt = System.currentTimeMillis();
        Long gueltigBis = nichtWiderrufenBis.get(jti);
        if (gueltigBis != null && gueltigBis > jetzt) {
            return false;
        }

        boolean widerrufen;
        try {
            widerrufen = lookup.isJtiRevoked(jti);
        } catch (RuntimeException e) {
            // FAIL-OPEN mit Absicht: Bei einem Datenbank-Aussetzer würde fail-closed JEDEN
            // MCP-Zugang kappen — für eine Lücke ohne bekannten Missbrauchsfall der falsche
            // Tausch. Es ist ausserdem die Konvention, die der Filter schon hat: keine
            // Checker-Bean = nichts gilt als widerrufen (siehe McpBearerTokenFilterConfig).
            log.warn("Widerruf-Pruefung fuer jti={} nicht moeglich, lasse durch: {}", jti, e.getMessage());
            return false;
        }

        if (widerrufen) {
            // Positive Treffer werden NICHT gecacht: Ein Widerruf wird nicht zurückgenommen, und
            // ein Eintrag hier würde nur unbegrenzt wachsen.
            nichtWiderrufenBis.remove(jti);
            return true;
        }

        if (nichtWiderrufenBis.size() > MAX_CACHE_EINTRAEGE) {
            // Schlichtes Leeren statt LRU: Der Cache ist eine Lastbremse, kein Korrektheitsmittel —
            // ein geleerter Cache kostet je Token einen Lookup, mehr nicht.
            log.debug("jti-Negativ-Cache uebersteigt {} Eintraege, wird geleert", MAX_CACHE_EINTRAEGE);
            nichtWiderrufenBis.clear();
        }
        nichtWiderrufenBis.put(jti, jetzt + NEGATIV_CACHE_TTL_MS);
        return false;
    }

    /**
     * Obergrenze gegen unbegrenztes Wachstum. Erfundene jti-Werte kommen hier nicht an — der Filter
     * prüft zuerst die Signatur —, aber jedes je ausgestellte Token hinterlässt einen Eintrag, und
     * ein Prozess läuft lange.
     */
    static final int MAX_CACHE_EINTRAEGE = 10_000;
}

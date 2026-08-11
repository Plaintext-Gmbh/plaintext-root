/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.apitoken;

import java.util.Optional;

/**
 * Schmaler, leak-freier Zugriff auf {@code api_token} für die Revocation-Prüfung im
 * MCP-Bearer-Filter.
 *
 * <p><b>Warum es diese Schnittstelle gibt (Karte 659, aus 655):</b> {@code spring.jpa.open-in-view}
 * steht auf dem Spring-Boot-Default {@code true} (in keinem der Repos abgeschaltet), und der
 * {@code OpenEntityManagerInViewFilter} umschliesst die gesamte Security-Filterkette. Der erste
 * JPA-Zugriff aus dem Filter bindet damit einen EntityManager an den Request und hält dessen
 * DB-Verbindung bis zum Requestende. Bei einer MCP-Sitzung
 * ({@code spring.ai.mcp.server.protocol: STREAMABLE}) ist das die <b>ganze Sitzungsdauer</b>;
 * HikariCP meldet die Verbindung nach 60 Sekunden als {@code Apparent connection leak detected}.
 * Gemessen in PROD: 15 solcher Warnungen in 7 Tagen, mit {@code ApiTokenService} im Stack.
 *
 * <p>Dieselbe Ursache wurde für den Rollen-Lookup im selben Filter bereits behoben
 * ({@link ch.plaintext.McpUserRoles}, Karte 437) — dort wie hier gilt: ein einzelner
 * JDBC-Zugriff öffnet die Verbindung, liest und gibt sie sofort zurück, ohne EntityManager, der
 * am Request hängen bliebe.
 *
 * <p><b>Zwei Methoden, nicht eine:</b> Der Best-effort-Schreibzugriff auf {@code last_used_at} /
 * {@code use_count} ist der zweite JPA-Zugriff im selben Pfad und bindet die Session genauso.
 * Ein Umbau, der nur den Lookup umstellt, verschiebt das Problem um zwei Zeilen.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public interface ApiTokenRevocationLookup {

    /**
     * Liest die Felder, die {@code ApiTokenService.validateVerifiedToken} für die
     * Revocation-Entscheidung braucht.
     *
     * @param tokenHash SHA-256-Hash des JWT (hex, 64 Zeichen); die Spalte ist unique
     * @return der Zustand des Tokens, oder leer, wenn zu diesem Hash kein Datensatz existiert
     *         (= widerrufen oder nie ausgestellt)
     */
    Optional<TokenZustand> findForValidation(String tokenHash);

    /**
     * Schreibt die Nutzungsstatistik fort: {@code last_used_at = jetzt},
     * {@code use_count = use_count + 1}, {@code updated_at = jetzt}.
     *
     * <p><b>Best effort</b> — schlägt der Schreibzugriff fehl, bleibt die bereits getroffene
     * Zugriffsentscheidung gültig. Die Auditspalten {@code last_modified_by} /
     * {@code last_modified_date} bleiben bewusst unberührt: Ein Token-<i>Gebrauch</i> ist keine
     * fachliche Änderung des Datensatzes, und der JPA-Auditor würde im Filterkontext
     * {@code "system"} eintragen und damit den letzten echten Bearbeiter überschreiben.
     *
     * @param id Primärschlüssel aus {@link #findForValidation(String)}
     */
    void markUsed(long id);

    /**
     * Die für die Zugriffsentscheidung gelesenen Felder eines Tokens.
     *
     * @param id          Primärschlüssel, für {@link #markUsed(long)}
     * @param deleted     {@code SuperModel.deleted}; die Spalte ist <b>nullable</b>, {@code NULL}
     *                    wird als {@code false} gelesen (nie gelöscht worden)
     * @param invalidated Soft-Invalidierung, Spalte {@code NOT NULL}
     * @param userEmail   Mailadresse des Besitzers, geht ins Validierungsergebnis ein
     */
    record TokenZustand(long id, boolean deleted, boolean invalidated, String userEmail) {
    }
}

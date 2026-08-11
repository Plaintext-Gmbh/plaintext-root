/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.apitoken;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@link ApiTokenRevocationLookup} über {@link JdbcTemplate} — Verbindung auf, lesen, sofort
 * zurück.
 *
 * <p>Die Klasse liegt bewusst in diesem Modul und nicht in {@code plaintext-root-webapp} (wo
 * {@code McpUserRolesImpl} steht): Tabelle, Entity, Repository und der einzige Aufrufer
 * ({@link ApiTokenService}) gehören alle zu {@code plaintext-admin-apitoken}. Bei
 * {@code McpUserRoles} lag die Tabelle {@code my_user_entity} in einem anderen Modul als der
 * Filter — nur deshalb brauchte es dort die Aufteilung über ein Interface-Modul.
 *
 * <p><b>Fachlich identisch zum bisherigen JPA-Weg</b> ({@code apiTokenRepository.findByTokenHash}
 * plus die beiden Feldprüfungen in {@code validateVerifiedToken}); festgehalten im Vertragstest
 * {@code ApiTokenRevocationVertragIT}, der beide Wege gegen denselben Datenbestand stellt.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
@Component
public class ApiTokenRevocationLookupJdbc implements ApiTokenRevocationLookup {

    private final JdbcTemplate jdbc;

    public ApiTokenRevocationLookupJdbc(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<TokenZustand> findForValidation(String tokenHash) {
        if (tokenHash == null || tokenHash.isEmpty()) {
            return Optional.empty();
        }
        // token_hash trägt uk_api_token_hash (unique) — höchstens eine Zeile. queryForList statt
        // queryForObject, damit "kein Treffer" ein leeres Optional ist und keine Exception.
        List<TokenZustand> treffer = jdbc.query(
                "SELECT id, deleted, invalidated, user_email FROM api_token WHERE token_hash = ?",
                // deleted ist NULLABLE (V1775256894); getBoolean() liefert für NULL false — das ist
                // die gewollte Lesart "nie gelöscht worden". Der JPA-Weg lief bei NULL in eine
                // NullPointerException (Unboxing von Boolean), also in einen 500er statt in eine
                // Zugriffsentscheidung; siehe Vertragstest.
                (rs, zeile) -> new TokenZustand(rs.getLong("id"), rs.getBoolean("deleted"),
                        rs.getBoolean("invalidated"), rs.getString("user_email")),
                tokenHash);
        return treffer.isEmpty() ? Optional.empty() : Optional.of(treffer.get(0));
    }

    @Override
    public boolean isJtiRevoked(String jti) {
        if (jti == null || jti.isEmpty()) {
            return false;
        }
        // Karte 664: Die Abfrage fragt direkt nach dem gesuchten Zustand, statt eine Zeile zu laden
        // und sie hier auszuwerten — der Aufruf sitzt im Auth-Pfad jedes MCP-Requests.
        // invalidated ist NOT NULL (V1775256894), deshalb genügt die WHERE-Bedingung; deleted wird
        // bewusst NICHT geprüft: invalidateToken() setzt beide Flags, und ein Datensatz, der nur
        // aufgeräumt (deleted) und nicht widerrufen wurde, ist kein Widerruf.
        Integer treffer = jdbc.queryForObject(
                "SELECT COUNT(*) FROM api_token WHERE jti = ? AND invalidated = TRUE",
                Integer.class, jti);
        return treffer != null && treffer > 0;
    }

    @Override
    public void markUsed(long id) {
        Timestamp jetzt = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("UPDATE api_token SET last_used_at = ?, use_count = use_count + 1, updated_at = ?"
                + " WHERE id = ?", jetzt, jetzt, id);
    }
}

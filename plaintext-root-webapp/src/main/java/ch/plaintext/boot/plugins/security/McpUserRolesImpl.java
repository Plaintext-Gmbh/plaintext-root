/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import ch.plaintext.McpUserRoles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link McpUserRoles}-Implementierung: liest die Rollen eines Benutzers für den MCP-Bearer-Filter.
 *
 * <p><b>Warum hier JDBC statt JPA steht (Karte 437):</b> Diese Methode läuft aus einem
 * Servlet-Filter heraus — auch beim <b>Verbindungsaufbau</b> einer MCP-Sitzung, einem Request, der
 * über die gesamte Sitzungsdauer offen bleibt. Mit {@code spring.jpa.open-in-view=true}
 * (Spring-Boot-Default, in keinem der Repos abgeschaltet) bindet der erste JPA-Zugriff einen
 * EntityManager an den Request; die DB-Verbindung ist damit für die ganze Sitzung belegt. HikariCP
 * meldet sie nach 60 Sekunden als {@code Apparent connection leak detected} — mit genau dieser
 * Methode im Stack.
 *
 * <p><b>Gemessen am 03.08.2026:</b> 424 solcher Warnungen in 24 Stunden, in app, guild und iot
 * <b>im selben Millisekundenfenster</b> — der Fingerabdruck eines MCP-Clients, der seine drei
 * Server gleichzeitig öffnet. Die Verbindungen kommen nach Sitzungsende zurück (kein unbegrenztes
 * Leck), aber solange die Sitzung steht, fehlen sie im Pool. Bei 25 Verbindungen Poolgrösse ist
 * die Grenze absehbar.
 *
 * <p>Ein einzelner {@code JdbcTemplate}-Zugriff öffnet die Verbindung, liest und gibt sie sofort
 * zurück — ohne EntityManager, der am Request hängen bliebe. Fachlich ist es derselbe Lesevorgang:
 * {@code MyUserEntity.roles} ist eine per {@code @Convert} gespeicherte Spalte, also ein einzelner
 * Spaltenwert ohne Lazy-Beziehung.
 *
 * <p><b>Nicht der Juli-Fix:</b> Damals (Memory {@code project_apitoken_connection_leak}) lag es an
 * {@code @Transactional} aus einem Filter heraus. Diese Klasse war nie {@code @Transactional} —
 * hier ist es die view-gebundene Session, ein anderer Mechanismus mit demselben Symptombild.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Component
public class McpUserRolesImpl implements McpUserRoles {

    /** XStream-Kopf des ROLES-Werts — die Spalte hält ein serialisiertes {@code Set<String>}. */
    private static final String ROLLE_ANFANG = "<string>";
    private static final String ROLLE_ENDE = "</string>";

    private final JdbcTemplate jdbc;

    public McpUserRolesImpl(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Set<String> rolesForUser(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        List<String> roh = jdbc.queryForList(
                "SELECT roles FROM my_user_entity WHERE id = ?", String.class, userId);
        if (roh.isEmpty() || roh.get(0) == null) {
            return Set.of();
        }
        return zerlege(roh.get(0));
    }

    /**
     * Zerlegt den gespeicherten XStream-Wert in die einzelnen Rollen.
     *
     * <p>Bewusst ohne XStream: Der Wert ist eine flache Liste von {@code <string>}-Elementen, und
     * der Filter soll für diesen einen Lesevorgang keinen Deserialisierer starten.
     */
    static Set<String> zerlege(String xml) {
        Set<String> rollen = new HashSet<>();
        int i = xml.indexOf(ROLLE_ANFANG);
        while (i >= 0) {
            int ende = xml.indexOf(ROLLE_ENDE, i);
            if (ende < 0) {
                break;
            }
            String wert = xml.substring(i + ROLLE_ANFANG.length(), ende).trim();
            if (!wert.isEmpty()) {
                rollen.add(wert);
            }
            i = xml.indexOf(ROLLE_ANFANG, ende);
        }
        return rollen;
    }
}

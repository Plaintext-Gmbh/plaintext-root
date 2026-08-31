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
 * {@link McpUserRoles} implementation: reads the roles of a user for the MCP bearer filter.
 *
 * <p><b>Why JDBC stands here instead of JPA (card 437):</b> this method runs out of a
 * servlet filter — including while an MCP session is being <b>established</b>, a request that
 * stays open for the whole duration of the session. With {@code spring.jpa.open-in-view=true}
 * (Spring Boot default, switched off in none of the repositories) the first JPA access binds an
 * entity manager to the request; the DB connection is thereby occupied for the whole session. After
 * 60 seconds HikariCP reports it as {@code Apparent connection leak detected} — with exactly this
 * method in the stack.
 *
 * <p><b>Measured on 03.08.2026:</b> 424 such warnings within 24 hours, in app, guild and iot
 * <b>within the same millisecond window</b> — the fingerprint of an MCP client that opens its three
 * servers simultaneously. The connections come back once the session ends (no unbounded
 * leak), but as long as the session stands they are missing from the pool. With a pool size of 25
 * connections the limit is foreseeable.
 *
 * <p>A single {@code JdbcTemplate} access opens the connection, reads and returns it immediately —
 * without an entity manager that would stay attached to the request. Functionally it is the same
 * read: {@code MyUserEntity.roles} is a column stored via {@code @Convert}, hence a single
 * column value without a lazy relation.
 *
 * <p><b>Not the July fix:</b> back then (memory {@code project_apitoken_connection_leak}) the cause
 * was {@code @Transactional} out of a filter. This class was never {@code @Transactional} —
 * here it is the view-bound session, a different mechanism with the same symptoms.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Component
public class McpUserRolesImpl implements McpUserRoles {

    /** XStream header of the ROLES value — the column holds a serialized {@code Set<String>}. */
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
     * Splits the stored XStream value into the individual roles.
     *
     * <p>Deliberately without XStream: the value is a flat list of {@code <string>} elements, and
     * the filter should not start a deserializer for this single read.
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

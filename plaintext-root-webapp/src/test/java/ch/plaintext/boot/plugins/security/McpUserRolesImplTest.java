/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Card 437: the MCP bearer filter must not bind a JPA session to a long-lived request.
 *
 * <p>The tests record both: that the stored role value is decomposed correctly, and — more
 * importantly — that the class no longer uses a JPA repository. Exactly that dependency was
 * the cause: with {@code open-in-view=true} the first JPA access in the filter binds an
 * EntityManager to the request, and in an MCP session it stays open for hours.
 */
class McpUserRolesImplTest {

    @Test
    void rollenWerdenAusDemGespeichertenWertGelesen() {
        String gespeichert = """
                <set>
                  <string>MEMBER</string>
                  <string>ADMIN</string>
                  <string>PROPERTY_MANDAT_DEFAULT</string>
                </set>""";
        assertEquals(Set.of("MEMBER", "ADMIN", "PROPERTY_MANDAT_DEFAULT"),
                McpUserRolesImpl.zerlege(gespeichert));
    }

    @Test
    void leereUndUnvollstaendigeWerteLiefernKeineRollen() {
        assertTrue(McpUserRolesImpl.zerlege("<set></set>").isEmpty());
        assertTrue(McpUserRolesImpl.zerlege("").isEmpty());
        assertTrue(McpUserRolesImpl.zerlege("<set><string>unfertig").isEmpty(),
                "ein abgeschnittener Wert darf keine halbe Rolle liefern");
        assertTrue(McpUserRolesImpl.zerlege("<set><string>  </string></set>").isEmpty(),
                "eine leere Rolle ist keine Rolle");
    }

    /**
     * The actual regression protection. If the repository came back, the connection leak would be
     * back as well — and invisibly so, because nothing changes functionally.
     */
    @Test
    void klasseHaengtNichtMehrAnJpa() {
        for (Constructor<?> c : McpUserRolesImpl.class.getDeclaredConstructors()) {
            for (Class<?> p : c.getParameterTypes()) {
                assertFalse(p.getName().contains("Repository"),
                        "McpUserRolesImpl bekommt wieder ein JPA-Repository (" + p.getSimpleName()
                                + "). Mit open-in-view=true bindet der erste JPA-Zugriff im Filter "
                                + "einen EntityManager an den Request — bei einer MCP-Sitzung "
                                + "belegt das die DB-Verbindung fuer die ganze Sitzung (Karte 437).");
            }
        }
        assertTrue(java.util.Arrays.stream(McpUserRolesImpl.class.getDeclaredFields())
                        .noneMatch(f -> f.getType().getName().contains("Repository")),
                "kein Repository-Feld — der Filter liest per JdbcTemplate");
    }
}

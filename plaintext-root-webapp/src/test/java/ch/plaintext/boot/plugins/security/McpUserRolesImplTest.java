/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Karte 437: Der MCP-Bearer-Filter darf keine JPA-Session an einen langlebigen Request binden.
 *
 * <p>Die Tests halten beides fest: dass der gespeicherte Rollenwert korrekt zerlegt wird, und —
 * wichtiger — dass die Klasse <b>kein</b> JPA-Repository mehr benutzt. Genau die Abhängigkeit war
 * die Ursache: Mit {@code open-in-view=true} bindet der erste JPA-Zugriff im Filter einen
 * EntityManager an den Request, und bei einer MCP-Sitzung bleibt der über Stunden offen.
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
     * Der eigentliche Regressionsschutz. Käme das Repository zurück, wäre auch der
     * Connection-Leak zurück — und zwar unsichtbar, weil sich fachlich nichts ändert.
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

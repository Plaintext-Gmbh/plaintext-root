/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Auftrag Daniel, 29.08.2026: {@code @Conditional}-Annotationen an {@code @MenuAnnotation}-Klassen
 * gelten — gegen die Umgebung der Anwendung. Das ist der Mechanismus, mit dem der Menuepunkt
 * „Swagger" verschwindet, wenn springdoc abgeschaltet ist.
 */
@DisplayName("MenuAnnotationScanner: bedingte Menuepunkte")
class MenuAnnotationScannerBedingungTest {

    private static final String PAKET = "ch.plaintext.boot.menu.bedingt";

    private static List<String> titel(MenuAnnotationScanner scanner) {
        return scanner.findAnnotatedClasses(PAKET).stream().map(MenuItemImpl::getTitle).sorted().toList();
    }

    @Test
    @DisplayName("Property gesetzt: der bedingte Menuepunkt ist da")
    void mitPropertyErscheintDerMenuepunkt() {
        MockEnvironment umgebung = new MockEnvironment().withProperty("test.menu.feature", "true");
        MenuAnnotationScanner scanner = new MenuAnnotationScanner(null, null, null).mitUmgebung(umgebung);

        assertEquals(List.of("Immer da", "Nur mit Property"), titel(scanner));
    }

    @Test
    @DisplayName("Property false oder fehlend: der bedingte Menuepunkt fehlt, der andere bleibt")
    void ohnePropertyFehltDerMenuepunkt() {
        MenuAnnotationScanner falsch = new MenuAnnotationScanner(null, null, null)
                .mitUmgebung(new MockEnvironment().withProperty("test.menu.feature", "false"));
        MenuAnnotationScanner leer = new MenuAnnotationScanner(null, null, null)
                .mitUmgebung(new MockEnvironment());

        assertEquals(List.of("Immer da"), titel(falsch));
        assertEquals(List.of("Immer da"), titel(leer));
    }

    @Test
    @DisplayName("Ohne Umgebung: Spring-Standard (System-Properties), nichts bricht")
    void ohneUmgebungWirdWeiterGescannt() {
        MenuAnnotationScanner scanner = new MenuAnnotationScanner(null, null, null);

        List<String> gefunden = titel(scanner);
        assertTrue(gefunden.contains("Immer da"), gefunden.toString());
    }
}

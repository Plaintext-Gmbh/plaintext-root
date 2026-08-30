/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.menuesteuerung.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The second form of list entries: {@code modul:<moduleId>} switches a whole module.
 *
 * <p>The focus of these tests is <b>losslessness</b>: no stored entry may change its meaning
 * because of the new feature. That is why only the explicit prefix form is a module entry — a bare
 * {@code moduleId} stays a menu title.</p>
 */
@DisplayName("MandateMenuConfig: Modul-Eintraege")
class MandateMenuConfigModulEintragTest {

    private static final String WIKI_MODUL = "modul:wiki";

    private static MandateMenuConfig config(boolean whitelist, String... eintraege) {
        MandateMenuConfig config = new MandateMenuConfig();
        config.setMandateName("testmandant");
        config.setWhitelistMode(whitelist);
        config.setHiddenMenus(new HashSet<>(Set.of(eintraege)));
        return config;
    }

    @Test
    @DisplayName("Der Praefix macht aus einem Eintrag einen Modul-Key")
    void praefixErkennung() {
        assertEquals("wiki", MandateMenuConfig.moduleKeyOf(WIKI_MODUL));
        assertEquals("wiki", MandateMenuConfig.moduleKeyOf("MODUL:WiKi"));
        assertEquals("wiki", MandateMenuConfig.moduleKeyOf("  modul: wiki  "));
    }

    @Test
    @DisplayName("VERLUSTFREI: eine nackte moduleId ist und bleibt ein Menue-Titel")
    void nackteModuleIdIstKeinModulEintrag() {
        assertEquals("", MandateMenuConfig.moduleKeyOf("wiki"),
                "Sonst wuerde ein Bestandseintrag 'wiki' ploetzlich ein ganzes Modul schalten");
        assertEquals("", MandateMenuConfig.moduleKeyOf("Wiki"));
        assertEquals("", MandateMenuConfig.moduleKeyOf("Wiki | Projekte"));
        assertEquals("", MandateMenuConfig.moduleKeyOf("modul:"), "leerer Key ist kein Modul-Eintrag");
        assertEquals("", MandateMenuConfig.moduleKeyOf(null));
    }

    @Test
    @DisplayName("VERLUSTFREI: ein Titel-Eintrag wirkt unveraendert, auch wenn er wie ein Modul heisst")
    void titelEintragWirktUnveraendert() {
        MandateMenuConfig config = config(false, "Wiki");

        // The title entry hits exactly its own menu item ...
        assertTrue(config.isListed("Wiki", List.of("wiki")));
        // ... but NOT the submenus of the module.
        assertFalse(config.isListed("Wiki | Projekte", List.of("wiki")),
                "Ein Titel-Eintrag darf kein ganzes Modul schalten");
    }

    @Test
    @DisplayName("Ein Modul-Eintrag erfasst das Wurzelmenue UND alle Untermenues")
    void modulEintragErfasstGanzesModul() {
        MandateMenuConfig config = config(false, WIKI_MODUL);

        assertTrue(config.isListed("Wiki", List.of("wiki")));
        assertTrue(config.isListed("Wiki | Projekte", List.of("wiki")));
        assertFalse(config.isListed("Kontakte", List.of("kontakte")));
    }

    @Test
    @DisplayName("Modul-Eintrag und Titel-Eintrag stehen nebeneinander")
    void beideFormenNebeneinander() {
        MandateMenuConfig config = config(false, WIKI_MODUL, "Kontakte | Liste");

        assertTrue(config.isListed("Wiki | Projekte", List.of("wiki")));
        assertTrue(config.isListed("Kontakte | Liste", List.of("kontakte")));
        assertFalse(config.isListed("Kontakte | Import", List.of("kontakte")));

        assertEquals(Set.of("wiki"), config.getModuleKeyEntries());
        assertEquals(Set.of("Kontakte | Liste"), config.getTitleEntries());
    }

    @Test
    @DisplayName("Modul-Keys werden case-insensitiv verglichen")
    void keyVergleichIstCaseInsensitiv() {
        MandateMenuConfig config = config(false, "modul:WIKI");

        assertTrue(config.isListed("Wiki | Projekte", List.of("wiki")));
    }

    @Test
    @DisplayName("Ohne Modul-Keys entscheidet allein der Titel")
    void ohneModulKeys() {
        MandateMenuConfig config = config(false, WIKI_MODUL);

        assertFalse(config.isListed("Wiki | Projekte", List.of()));
        assertFalse(config.isListed("Wiki | Projekte", null));
    }

    @Test
    @DisplayName("moduleEntryOf baut die kanonische Form")
    void eintragBauen() {
        assertEquals(WIKI_MODUL, MandateMenuConfig.moduleEntryOf("WIKI"));
        assertEquals(WIKI_MODUL, MandateMenuConfig.moduleEntryOf(" wiki "));
        assertEquals("", MandateMenuConfig.moduleEntryOf("  "));
        assertEquals("", MandateMenuConfig.moduleEntryOf(null));
    }

    @Test
    @DisplayName("isMenuHidden bleibt unveraendert: exakter Titel-Vergleich")
    void isMenuHiddenUnveraendert() {
        MandateMenuConfig config = config(false, "Wiki | Projekte");

        assertTrue(config.isMenuHidden("Wiki | Projekte"));
        assertFalse(config.isMenuHidden("wiki | projekte"));
        assertFalse(config.isMenuHidden("Wiki | Projekte "));
    }
}

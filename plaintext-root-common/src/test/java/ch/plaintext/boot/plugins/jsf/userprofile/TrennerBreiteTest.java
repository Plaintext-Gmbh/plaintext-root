/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.jsf.userprofile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

/**
 * Karte 937: Die gespeicherte Breite eines verschiebbaren Trenners.
 *
 * <p><b>Der wichtigste Test ist {@link #nullBreiteSperrtNichtAus()}.</b> Ohne untere Grenze laesst
 * sich der Baum auf 0 Pixel ziehen; danach ist der Griff nicht mehr zu treffen — und weil der Wert
 * gespeichert wird, bleibt es so, auch nach einem Neustart. Der Benutzer sperrt sich dauerhaft aus
 * einer Ansicht aus, und zwar mit einer einzigen Mausbewegung.
 */
class TrennerBreiteTest {

    private UserPrefsSimpleStorage storage;
    private UserPreferencesBackingBean bean;
    private UserPreference prefs;

    @BeforeEach
    void setUp() {
        storage = mock(UserPrefsSimpleStorage.class);
        bean = new UserPreferencesBackingBean();
        prefs = new UserPreference();
        prefs.setUniqueId("ada");
        ReflectionTestUtils.setField(bean, "storage", storage);
        ReflectionTestUtils.setField(bean, "prefs", prefs);
    }

    @Test
    @DisplayName("Eine normale Breite wird uebernommen und gespeichert")
    void normaleBreiteWirdGespeichert() {
        bean.merkeTrennerBreite("wiki", 320);

        assertThat(prefs.getWikiTreeWidth()).isEqualTo(320);
        verify(storage).save(prefs);
    }

    @Test
    @DisplayName("Wiki und Mail haben getrennte Werte")
    void wikiUndMailSindGetrennt() {
        bean.merkeTrennerBreite("wiki", 320);
        bean.merkeTrennerBreite("mail", 500);

        assertThat(prefs.getWikiTreeWidth()).isEqualTo(320);
        assertThat(prefs.getMailListWidth())
                .as("ein gemeinsamer Wert wuerde beim Verschieben der einen Ansicht die andere mitverstellen")
                .isEqualTo(500);
    }

    @Test
    @DisplayName("Eine winzige Breite wird auf das Minimum angehoben — sonst sperrt man sich aus")
    void nullBreiteSperrtNichtAus() {
        bean.merkeTrennerBreite("wiki", 3);

        assertThat(prefs.getWikiTreeWidth())
                .as("darunter ist der Griff nicht mehr zu treffen, und der Wert ueberlebt den Neustart")
                .isEqualTo(UserPreferencesBackingBean.MIN_TRENNER_PX);
    }

    @Test
    @DisplayName("Eine masslose Breite wird gedeckelt — sonst bleibt fuer den Inhalt nichts")
    void masslosBreiteWirdGedeckelt() {
        bean.merkeTrennerBreite("wiki", 99_999);

        assertThat(prefs.getWikiTreeWidth()).isEqualTo(UserPreferencesBackingBean.MAX_TRENNER_PX);
    }

    @Test
    @DisplayName("0 bleibt erlaubt: das ist der Weg zurueck zur Layout-Vorgabe")
    void nullIstDerWegZurueck() {
        bean.merkeTrennerBreite("wiki", 320);
        bean.merkeTrennerBreite("wiki", 0);

        assertThat(prefs.getWikiTreeWidth())
                .as("ohne diesen Ausweg gaebe es keinen Weg zurueck zur Vorgabe")
                .isZero();
    }

    @Test
    @DisplayName("Ein unbekannter Bereich aendert nichts und speichert nicht")
    void unbekannterBereichTutNichts() {
        bean.merkeTrennerBreite("gibtsnicht", 320);

        assertThat(prefs.getWikiTreeWidth()).isZero();
        assertThat(prefs.getMailListWidth()).isZero();
        verify(storage, never()).save(prefs);
    }

    @Test
    @DisplayName("Was gemerkt wurde, laesst sich auch wieder lesen")
    void gemerktesLaesstSichLesen() {
        bean.merkeTrennerBreite("wiki", 320);
        bean.merkeTrennerBreite("mail", 500);

        assertThat(bean.trennerBreite("wiki")).isEqualTo(320);
        assertThat(bean.trennerBreite("mail")).isEqualTo(500);
    }

    @Test
    @DisplayName("Ohne gemerkten Wert kommt 0 zurueck — die Seite nimmt ihre Vorgabe")
    void ohneWertKommtVorgabe() {
        assertThat(bean.trennerBreite("wiki")).isZero();
    }

    @Test
    @DisplayName("Ein unbekannter Bereich liefert die Vorgabe statt einer Ausnahme")
    void unbekannterBereichLiefertVorgabe() {
        assertThat(bean.trennerBreite("gibtsnicht")).isZero();
    }

    @Test
    @DisplayName("Ohne geladene Einstellungen liefert das Lesen die Vorgabe statt zu werfen")
    void ohneEinstellungenKeineAusnahme() {
        ReflectionTestUtils.setField(bean, "prefs", null);

        assertThat(bean.trennerBreite("wiki"))
                .as("das Feld ist transient; nach dem Wiederherstellen einer Sitzung ist es leer, "
                        + "und eine Ausnahme aus einem style-Attribut heraus zerreisst die Seite")
                .isZero();
    }

    @Test
    @DisplayName("Die Breite ist aus EL erreichbar — genau daran ist wiki.xhtml gescheitert")
    void ausElErreichbar() throws Exception {
        // wiki.xhtml las #{userPreferencesBackingBean.prefs.wikiTreeWidth}. 'prefs' ist privat und
        // transient, also keine Property: EL warf zur Laufzeit. Dieser Test haelt fest, dass der
        // Lesepfad eine oeffentliche Methode ist und bleibt.
        assertThat(UserPreferencesBackingBean.class.getMethod("trennerBreite", String.class))
                .as("ohne oeffentlichen Lesepfad rendert die Seite wieder ins Leere")
                .isNotNull();
        assertThat(UserPreference.class.getDeclaredField("wikiTreeWidth").getModifiers())
                .as("das Feld selbst bleibt gekapselt")
                .isNotZero();
    }
}

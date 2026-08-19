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
}

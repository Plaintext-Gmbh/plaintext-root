/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.plugins.jsf.userprofile.UserPreferencesBackingBean;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyRememberMeRepository;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import jakarta.faces.model.SelectItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Auftrag Daniel, 25.08.2026: „gerne oben noch eine Auswahl mit Checkbox, dass man sich pro
 * Benutzer die Auswahl der Spalten speichern kann. Immer wenn man dort aufklappt und die
 * Spaltenauswahl macht, soll gespeichert werden."
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UseradminSpaltenauswahlTest {

    @Mock private MyUserRepository repo;
    @Mock private PlaintextSecurity plaintextSecurity;
    @Mock private MyRememberMeRepository rememberMeRepo;
    @Mock private UserPreferencesBackingBean userPreferences;

    private MyUserBackingBean bean;

    private static MyUserEntity user(long id, String name, String mandat) {
        MyUserEntity u = new MyUserEntity();
        u.setId(id);
        u.setUsername(name);
        if (mandat != null) {
            u.setMandat(mandat);
        }
        return u;
    }

    @BeforeEach
    void setUp() {
        when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(true);
        when(repo.findAll()).thenReturn(List.of(
                user(1L, "a@x.ch", "plaintext"),
                user(2L, "b@x.ch", "trimstein"),
                user(3L, "c@x.ch", "plaintext"),
                user(4L, "ohne@x.ch", null)));
        bean = new MyUserBackingBean(null, repo, null, rememberMeRepo, plaintextSecurity,
                null, null, null, userPreferences);
    }

    // ---------------------------------------------------------------- Voreinstellung / Laden

    @Test
    @DisplayName("Ohne gespeicherte Auswahl gilt die Voreinstellung - der bisherige Bestand")
    void ohneGespeichertesGiltDieVoreinstellung() {
        when(userPreferences.tabellenSpalten(MyUserBackingBean.TABELLE)).thenReturn(null);

        bean.init();

        assertThat(bean.getSichtbareSpalten())
                .contains("username", "mandat", "startpage", "id", "remember", "impersonate")
                .contains("vorname", "nachname");
    }

    @Test
    @DisplayName("Eine gespeicherte Auswahl wird uebernommen")
    void gespeicherteAuswahlWirdUebernommen() {
        when(userPreferences.tabellenSpalten(MyUserBackingBean.TABELLE))
                .thenReturn(new ArrayList<>(List.of("username", "mandat")));

        bean.init();

        assertThat(bean.getSichtbareSpalten()).containsExactly("username", "mandat");
        assertThat(bean.spalteSichtbar("username")).isTrue();
        assertThat(bean.spalteSichtbar("startpage")).isFalse();
    }

    /**
     * Der Unterschied, an dem so etwas sonst scheitert: „nie etwas ausgewaehlt" ({@code null})
     * und „ausdruecklich nichts ausgewaehlt" (leere Liste) sind zweierlei. Wuerde die leere Liste
     * wie {@code null} behandelt, kaeme die volle Voreinstellung zurueck — der Benutzer haette
     * abgewaehlt und beim naechsten Anmelden staende wieder alles da.
     */
    @Test
    @DisplayName("Eine ausdruecklich leere Auswahl bleibt leer")
    void leereAuswahlBleibtLeer() {
        when(userPreferences.tabellenSpalten(MyUserBackingBean.TABELLE)).thenReturn(new ArrayList<>());

        bean.init();

        assertThat(bean.getSichtbareSpalten()).isEmpty();
        assertThat(bean.spalteSichtbar("username")).isFalse();
    }

    // ---------------------------------------------------------------- Speichern

    @Test
    @DisplayName("Jede Aenderung wird sofort gespeichert - ohne Speichern-Knopf")
    void aenderungWirdSofortGespeichert() {
        bean.init();
        bean.setSichtbareSpalten(new ArrayList<>(List.of("username", "nachname")));

        bean.spaltenGeaendert();

        verify(userPreferences).merkeTabellenSpalten(MyUserBackingBean.TABELLE,
                List.of("username", "nachname"));
    }

    @Test
    @DisplayName("Ohne Benutzereinstellungen wird nichts gespeichert und nichts geworfen")
    void ohneEinstellungenKeinFehler() {
        MyUserBackingBean ohne = new MyUserBackingBean(null, repo, null, rememberMeRepo, plaintextSecurity,
                null, null, null, null);
        ohne.init();

        ohne.spaltenGeaendert();

        assertThat(ohne.getSichtbareSpalten()).isNotEmpty();
        verify(userPreferences, never()).merkeTabellenSpalten(anyString(), any());
    }

    // ---------------------------------------------------------------- Mandanten-Mehrfachfilter

    @Test
    @DisplayName("Der Mandantenfilter zeigt jeden vorkommenden Mandanten genau einmal, sortiert")
    void mandatFilterIstEindeutigUndSortiert() {
        bean.init();

        List<String> werte = bean.getMandatFilterAuswahl().stream()
                .map(i -> String.valueOf(i.getValue())).toList();

        assertThat(werte).containsExactly("", "plaintext", "trimstein");
    }

    /**
     * Ein Benutzer ohne Mandant darf nicht unauffindbar werden. Er bekommt einen eigenen,
     * benannten Eintrag statt eines leeren Kaestchens.
     */
    @Test
    @DisplayName("Benutzer ohne Mandant sind ueber einen eigenen Eintrag auffindbar")
    void ohneMandantEigenerEintrag() {
        bean.init();

        SelectItem leer = bean.getMandatFilterAuswahl().stream()
                .filter(i -> "".equals(i.getValue())).findFirst().orElseThrow();

        assertThat(leer.getLabel()).isEqualTo("(ohne Mandant)");
    }

    // ---------------------------------------------------------------- Anzeigename

    @Test
    @DisplayName("Der Anzeigename haelt auch aus, wenn nur ein Teil gesetzt ist")
    void anzeigename() {
        MyUserEntity u = new MyUserEntity();
        assertThat(u.getAnzeigename()).isEmpty();

        u.setVorname("Jerome");
        assertThat(u.getAnzeigename()).isEqualTo("Jerome");

        u.setNachname("Trimstein");
        assertThat(u.getAnzeigename()).isEqualTo("Jerome Trimstein");

        u.setVorname(null);
        assertThat(u.getAnzeigename()).isEqualTo("Trimstein");
    }
}

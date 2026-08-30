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
 * Request by Daniel, 25.08.2026: "I would also like a selection with a checkbox at the top, so that
 * the choice of columns can be saved per user. Whenever you open that up and make the
 * column selection, it should be saved."
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

    // ------------------------------------------------------------- Default setting / loading

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
     * The distinction on which such a feature otherwise founders: "never selected anything"
     * ({@code null}) and "explicitly selected nothing" (empty list) are two different things. If the
     * empty list were treated like {@code null}, the full default setting would come back — the user
     * would have deselected and on the next login everything would be there again.
     */
    @Test
    @DisplayName("Eine ausdruecklich leere Auswahl bleibt leer")
    void leereAuswahlBleibtLeer() {
        when(userPreferences.tabellenSpalten(MyUserBackingBean.TABELLE)).thenReturn(new ArrayList<>());

        bean.init();

        assertThat(bean.getSichtbareSpalten()).isEmpty();
        assertThat(bean.spalteSichtbar("username")).isFalse();
    }

    // ------------------------------------------------------------------- Saving

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

    // ------------------------------------------------------------------ Multiple tenant filter

    @Test
    @DisplayName("Der Mandantenfilter zeigt jeden vorkommenden Mandanten genau einmal, sortiert")
    void mandatFilterIstEindeutigUndSortiert() {
        bean.init();

        List<String> werte = bean.getMandatFilterAuswahl().stream()
                .map(i -> String.valueOf(i.getValue())).toList();

        assertThat(werte).containsExactly("", "plaintext", "trimstein");
    }

    /**
     * A user without a tenant must not become impossible to find. They get their own,
     * named entry instead of an empty box.
     */
    @Test
    @DisplayName("Benutzer ohne Mandant sind ueber einen eigenen Eintrag auffindbar")
    void ohneMandantEigenerEintrag() {
        bean.init();

        SelectItem leer = bean.getMandatFilterAuswahl().stream()
                .filter(i -> "".equals(i.getValue())).findFirst().orElseThrow();

        assertThat(leer.getLabel()).isEqualTo("(ohne Mandant)");
    }

    // --------------------------------------------------------------- Display name

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

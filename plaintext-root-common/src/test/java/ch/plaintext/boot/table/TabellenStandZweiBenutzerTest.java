/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.table;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.plugins.jsf.userprofile.UserPreference;
import ch.plaintext.boot.plugins.jsf.userprofile.UserPreferencesBackingBean;
import ch.plaintext.boot.plugins.jsf.userprofile.UserPrefsSimpleStorage;
import ch.plaintext.boot.plugins.objstore.SimpleStorableConverter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Karte 1336, Pruefung 6: der Tabellenstand gehoert der Person — mit zwei Benutzern gezeigt.
 *
 * <p><b>Was hier echt ist und was nicht.</b> Echt sind die ganze Kette oberhalb der Datenbank:
 * {@link TableSettings} → {@link UserPreferenceTableStateStore} → {@link UserPreferencesBackingBean}
 * (das Session-Bean, ueber das jeder Stand laeuft) → {@link UserPrefsSimpleStorage}, und der
 * Konverter der Spalte ({@link SimpleStorableConverter}) mit seinem Default-Typing. Ersetzt ist nur
 * die Tabelle {@code simple_storable_entity}: eine Map vom Benutzer auf den JSON-Text, den der
 * Konverter schreiben wuerde. Jedes Laden liest diesen Text neu — ein "Neuladen" ist hier deshalb
 * ein echter Weg durch das JSON und nicht die Rueckgabe desselben Objekts.</p>
 *
 * <p><b>"Neuladen" heisst: neue Session.</b> Das Bean ist session-scoped. Abmelden und wieder
 * anmelden ergibt ein neues Bean-Exemplar, das im {@code @PostConstruct} die Einstellungen des
 * angemeldeten Benutzers aus der Ablage holt — genau das bildet {@link #sitzung(String)} nach.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class TabellenStandZweiBenutzerTest {

    private static final String SEITE = "guild-member";

    private static final List<TableColumn> SPALTEN = List.of(
            new TableColumn("nr", "Nr", 80),
            new TableColumn("name", "Name", 200),
            new TableColumn("typ", "Typ", 120),
            new TableColumn("notiz", "Notiz", 300, false));

    private JsonAblage ablage;

    private PlaintextSecurity security;

    @BeforeEach
    void setUp() {
        ablage = new JsonAblage();
        security = mock(PlaintextSecurity.class);
        when(security.getMandat()).thenReturn("guild42");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("A richtet ein, B sieht die Vorgabe, A findet nach dem Neuladen alles wieder")
    void zweiBenutzer() {
        // ── A richtet die Tabelle ein ───────────────────────────────────────
        TableSettings a = sitzung("anna@guild42.ch");
        a.setVisibleColumns(List.of("nr", "name", "notiz"));        // typ aus, notiz an
        a.persist();
        a.setGesamtBreite(1000);
        a.breitenKnopfGedrueckt();                                   // Breiten proportional verteilt
        a.setPaginatorOben(false);
        a.onPaginatorChange(null);
        a.setSeitengroesse(1000);
        a.onSeitengroesseChange(null);
        a.setNewProfileName("Buchhaltung");
        a.createProfile();

        String breiteNrBeiA = a.widthStyle("nr");
        assertThat(breiteNrBeiA).isNotEqualTo("width:80px;");       // Positivkontrolle: A hat verstellt

        // Positivkontrolle an der Ablage selbst: die neuen Felder stehen im JSON des Benutzers A.
        assertThat(ablage.json("anna@guild42.ch"))
                .contains("\"guild42/guild-member\"")
                .contains("\"paginatorTop\":false")
                .contains("\"rowsPerPage\":1000")
                .contains("Buchhaltung");

        // ── B meldet sich an: nichts von A ─────────────────────────────────
        TableSettings b = sitzung("bruno@guild42.ch");
        assertThat(b.getVisibleColumns()).containsExactly("nr", "name", "typ");
        assertThat(b.widthStyle("nr")).isEqualTo("width:80px;");
        assertThat(b.getPaginatorPosition()).isEqualTo("both");
        assertThat(b.getRows()).isEqualTo(200);
        assertThat(b.getProfileNames()).containsExactly(TableSettings.PROFILE_DEFAULT);

        // B verstellt selbst etwas — das darf A ebenso wenig treffen.
        b.setPaginatorUnten(false);
        b.onPaginatorChange(null);

        // ── A meldet sich neu an (neue Session, neues Bean) ────────────────
        TableSettings aNeu = sitzung("anna@guild42.ch");
        assertThat(aNeu).isNotSameAs(a);
        assertThat(aNeu.getVisibleColumns()).containsExactly("nr", "name", "notiz");
        assertThat(aNeu.widthStyle("nr")).isEqualTo(breiteNrBeiA);
        assertThat(aNeu.getGesamtBreite()).isEqualTo(1000);
        assertThat(aNeu.getPaginatorPosition()).isEqualTo("bottom");
        assertThat(aNeu.getRows()).isEqualTo(1000);
        assertThat(aNeu.getProfileNames()).containsExactlyInAnyOrder(TableSettings.PROFILE_DEFAULT, "Buchhaltung");
        assertThat(aNeu.getSelectedProfile()).isEqualTo("Buchhaltung");

        // ── und B nach dem Neuladen hat nur, was B selbst verstellt hat ────
        TableSettings bNeu = sitzung("bruno@guild42.ch");
        assertThat(bNeu.getPaginatorPosition()).isEqualTo("top");
        assertThat(bNeu.getRows()).isEqualTo(200);
        assertThat(bNeu.getVisibleColumns()).containsExactly("nr", "name", "typ");
    }

    @Test
    @DisplayName("Ein gespeicherter Stand von vor Karte 1336 (ohne die neuen Felder) ergibt das bisherige Verhalten")
    void altstandOhneNeueFelder() {
        TableSettings a = sitzung("anna@guild42.ch");
        a.setVisibleColumns(List.of("nr", "name"));
        a.persist();

        // Den Stand so zurueckschneiden, wie ihn root 1.729.0 geschrieben hat: ohne die drei Felder.
        String neu = ablage.json("anna@guild42.ch");
        String alt = neu.replaceAll(",\"(paginatorTop|paginatorBottom|rowsPerPage)\":null", "");
        assertThat(neu).contains("paginatorTop");                            // Positivkontrolle
        assertThat(alt).doesNotContain("paginatorTop").doesNotContain("paginatorBottom").doesNotContain("rowsPerPage");
        ablage.setzeJson("anna@guild42.ch", alt);

        TableSettings aNeu = sitzung("anna@guild42.ch");
        assertThat(aNeu.getVisibleColumns()).containsExactly("nr", "name");   // der Rest ist noch da
        assertThat(aNeu.getPaginatorPosition()).isEqualTo("both");
        assertThat(aNeu.isPaginator()).isTrue();
        assertThat(aNeu.getRows()).isEqualTo(200);
    }

    /**
     * Der Weg, den der Browser wirklich geht, und der Fehler, den der Zwei-Benutzer-Durchgang im
     * Browser gefunden hat: das Session-Bean entsteht schon auf der Anmeldeseite
     * ({@code #{i18n.t(...)}} fragt nach der Sprache), also fuer {@code anonymousUser}, und die
     * Session ueberlebt die Anmeldung. Bis Karte 1336 schrieb danach jeder Benutzer in den
     * Datensatz {@code anonymousUser} — B sah, was A eingerichtet hatte.
     */
    @Test
    @DisplayName("Bean entsteht vor der Anmeldung (Anmeldeseite): trotzdem speichert jeder bei sich, nie bei anonymousUser")
    void beanVonDerAnmeldeseite() {
        // A: Anmeldeseite, dann Anmeldung — dasselbe Bean-Exemplar in derselben Session.
        UserPreferencesBackingBean sessionA = anonymeSitzung();
        anmelden("anna@guild42.ch");
        TableSettings a = anzeige(sessionA);
        a.setPaginatorOben(false);
        a.onPaginatorChange(null);
        a.setVisibleColumns(List.of("nr"));
        a.persist();

        assertThat(ablage.benutzer()).doesNotContain("anonymousUser").contains("anna@guild42.ch");
        assertThat(ablage.json("anna@guild42.ch")).contains("\"paginatorTop\":false");

        // B: ebenso ueber die Anmeldeseite — und sieht nichts von A.
        UserPreferencesBackingBean sessionB = anonymeSitzung();
        anmelden("bruno@guild42.ch");
        TableSettings b = anzeige(sessionB);
        assertThat(b.getPaginatorPosition()).isEqualTo("both");
        assertThat(b.getVisibleColumns()).containsExactly("nr", "name", "typ");

        assertThat(ablage.benutzer()).doesNotContain("anonymousUser");
    }

    /** Eine Session, deren Bean auf der Anmeldeseite entstanden ist — so wie I18nEL es ausloest. */
    private UserPreferencesBackingBean anonymeSitzung() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "schluessel", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        UserPreferencesBackingBean prefs = new UserPreferencesBackingBean();
        ReflectionTestUtils.setField(prefs, "storage", ablage);
        prefs.init();
        prefs.getLanguage();                  // was I18nEL.resolveUserLanguage() auf der Anmeldeseite tut
        prefs.setLanguage("de");              // und ein Speichern ohne Anmeldung darf nichts anlegen
        assertThat(ablage.benutzer()).doesNotContain("anonymousUser");
        return prefs;
    }

    private static void anmelden(String benutzer) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(benutzer, "x", List.of()));
    }

    private TableSettings anzeige(UserPreferencesBackingBean prefs) {
        TableSettings anzeige = new TableSettings(SEITE, true, 200);
        anzeige.init(new UserPreferenceTableStateStore(prefs, security), SPALTEN);
        return anzeige;
    }

    /**
     * Eine neue Session fuer {@code benutzer}: angemeldet, frisches Session-Bean, frische Seite —
     * so, wie es nach Abmelden und Anmelden (oder in einem zweiten Browser) aussieht.
     */
    private TableSettings sitzung(String benutzer) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(benutzer, "x", List.of()));
        UserPreferencesBackingBean prefs = new UserPreferencesBackingBean();
        ReflectionTestUtils.setField(prefs, "storage", ablage);
        prefs.init();

        TableSettings anzeige = new TableSettings(SEITE, true, 200);
        anzeige.init(new UserPreferenceTableStateStore(prefs, security), SPALTEN);
        return anzeige;
    }

    /**
     * Die Datenbank-Ablage ohne Datenbank: je Benutzer der JSON-Text, den der echte Konverter
     * in die Spalte schreiben wuerde. Laden liest ihn jedes Mal neu.
     */
    static final class JsonAblage extends UserPrefsSimpleStorage {

        private final SimpleStorableConverter konverter = new SimpleStorableConverter();

        private final Map<String, String> zeilen = new HashMap<>();

        @Override
        public void save(UserPreference object) {
            zeilen.put(object.getUniqueId(), konverter.convertToDatabaseColumn(object));
        }

        @Override
        public UserPreference findByUniqueId(String uniqueId) {
            String json = zeilen.get(uniqueId);
            return json == null ? null : (UserPreference) konverter.convertToEntityAttribute(json);
        }

        String json(String benutzer) {
            return zeilen.get(benutzer);
        }

        void setzeJson(String benutzer, String json) {
            zeilen.put(benutzer, json);
        }

        java.util.Set<String> benutzer() {
            return zeilen.keySet();
        }
    }
}

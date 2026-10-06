/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.startseite;

import ch.plaintext.boot.plugins.jsf.userprofile.UserPreference;
import ch.plaintext.boot.plugins.jsf.userprofile.UserPreferencesBackingBean;
import ch.plaintext.boot.plugins.jsf.userprofile.UserPrefsSimpleStorage;
import ch.plaintext.boot.plugins.objstore.SimpleStorableConverter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Karte 1351: die Anordnung der Startseite gehoert der Person und dem Mandanten — und sie
 * uebersteht den Weg durch das JSON der Ablage ({@code SimpleStorableConverter}), also Abmelden
 * und Deploy. Gebaut wie {@code TabellenStandZweiBenutzerTest}: jede "Sitzung" ist ein neues
 * Session-Bean, das den Stand des angemeldeten Benutzers frisch aus der Ablage liest.
 */
@DisplayName("Karte 1351: Startseiten-Layout je Benutzer und Mandant")
class StartseiteZweiBenutzerTest {

    private final JsonAblage ablage = new JsonAblage();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("A richtet ein, B sieht nichts davon, A findet nach neuer Sitzung alles wieder")
    void zweiBenutzer() {
        StartseitenLayout layoutA = new StartseitenLayout(List.of(
                new StartseitenLayout.Eintrag("kalorien", true, false),
                new StartseitenLayout.Eintrag("kontakte", false, true)));

        UserPreferencesBackingBean a = sitzung("anna");
        assertThat(a.startseite("plaintext")).isNull(); // nie eingerichtet
        a.merkeStartseite("plaintext", layoutA);

        // Positivkontrolle an der Ablage: das Layout steht im JSON von A.
        assertThat(ablage.json("anna")).contains("\"startseiten\"").contains("kalorien").contains("kontakte");

        UserPreferencesBackingBean b = sitzung("bruno");
        assertThat(b.startseite("plaintext")).isNull();
        assertThat(ablage.json("bruno")).doesNotContain("kalorien");

        UserPreferencesBackingBean aNeu = sitzung("anna");
        StartseitenLayout gelesen = aNeu.startseite("plaintext");
        assertThat(gelesen).isNotNull();
        assertThat(gelesen.getEintraege()).containsExactly(
                new StartseitenLayout.Eintrag("kalorien", true, false),
                new StartseitenLayout.Eintrag("kontakte", false, true));

        // Anderer Mandant derselben Person: eigener, leerer Stand.
        assertThat(aNeu.startseite("guild42")).isNull();
    }

    @Test
    @DisplayName("Standard wiederherstellen entfernt den Stand in der Ablage")
    void standardWiederherstellen() {
        UserPreferencesBackingBean a = sitzung("anna");
        a.merkeStartseite("plaintext", new StartseitenLayout(List.of(
                new StartseitenLayout.Eintrag("kalorien", false, false))));
        assertThat(sitzung("anna").startseite("plaintext")).isNotNull();

        sitzung("anna").merkeStartseite("plaintext", null);

        assertThat(sitzung("anna").startseite("plaintext")).isNull();
    }

    /**
     * Ein Datensatz aus der Zeit vor Karte 1351 kennt das Feld nicht: er muss sich lesen lassen,
     * und die Startseite startet mit der Vorgabe.
     */
    @Test
    @DisplayName("Alter Datensatz ohne Feld 'startseiten' laesst sich lesen")
    void alterDatensatz() throws Exception {
        UserPreference alt = new UserPreference();
        alt.setUniqueId("carla");
        ablage.save(alt);
        tools.jackson.databind.node.ObjectNode baum =
                (tools.jackson.databind.node.ObjectNode) new tools.jackson.databind.ObjectMapper()
                        .readTree(ablage.json("carla"));
        assertThat(baum.has("startseiten")).isTrue(); // Positivkontrolle: das Feld wird geschrieben
        baum.remove("startseiten");
        String ohneFeld = baum.toString();
        assertThat(ohneFeld).doesNotContain("startseiten");
        ablage.setzeJson("carla", ohneFeld);

        UserPreferencesBackingBean c = sitzung("carla");
        assertThat(c.startseite("plaintext")).isNull();
        c.merkeStartseite("plaintext", new StartseitenLayout(List.of(new StartseitenLayout.Eintrag("a", true, true))));
        assertThat(sitzung("carla").startseite("plaintext").getEintraege()).hasSize(1);
    }

    private UserPreferencesBackingBean sitzung(String benutzer) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(benutzer, "x", List.of()));
        UserPreferencesBackingBean prefs = new UserPreferencesBackingBean();
        ReflectionTestUtils.setField(prefs, "storage", ablage);
        prefs.init();
        return prefs;
    }

    /** Die Ablage ohne Datenbank: je Benutzer der JSON-Text, den der echte Konverter schreibt. */
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
    }
}

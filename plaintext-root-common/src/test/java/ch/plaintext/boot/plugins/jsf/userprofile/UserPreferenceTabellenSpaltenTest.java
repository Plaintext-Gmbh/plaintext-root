/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.boot.plugins.jsf.userprofile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Alt-Datensaetze aus der XStream-Deserialisierung tragen {@code tabellenSpalten = null},
 * weil der Feld-Initializer dort nie laeuft — der Getter muss das abfangen (guild-PROD-500
 * vom 28.08.2026 auf der Benutzerverwaltung).
 */
class UserPreferenceTabellenSpaltenTest {

    @Test
    void getterLiefertNieNullAuchWennDasFeldNullIst() throws Exception {
        UserPreference pref = new UserPreference();
        var feld = UserPreference.class.getDeclaredField("tabellenSpalten");
        feld.setAccessible(true);
        feld.set(pref, null); // XStream-Altbestand nachgestellt

        assertThat(pref.getTabellenSpalten()).isNotNull().isEmpty();
        pref.getTabellenSpalten().put("useradmin", List.of("email"));
        assertThat(pref.getTabellenSpalten()).containsKey("useradmin");
    }
}

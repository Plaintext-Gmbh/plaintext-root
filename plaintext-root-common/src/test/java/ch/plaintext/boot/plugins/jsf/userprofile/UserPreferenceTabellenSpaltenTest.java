/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.boot.plugins.jsf.userprofile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Old records from the XStream deserialization carry {@code tabellenSpalten = null},
 * because the field initializer never runs there — the getter has to catch that (the guild
 * PROD 500 of 28.08.2026 on the user administration).
 */
class UserPreferenceTabellenSpaltenTest {

    @Test
    void getterLiefertNieNullAuchWennDasFeldNullIst() throws Exception {
        UserPreference pref = new UserPreference();
        var feld = UserPreference.class.getDeclaredField("tabellenSpalten");
        feld.setAccessible(true);
        feld.set(pref, null); // XStream legacy data reproduced

        assertThat(pref.getTabellenSpalten()).isNotNull().isEmpty();
        pref.getTabellenSpalten().put("useradmin", List.of("email"));
        assertThat(pref.getTabellenSpalten()).containsKey("useradmin");
    }
}

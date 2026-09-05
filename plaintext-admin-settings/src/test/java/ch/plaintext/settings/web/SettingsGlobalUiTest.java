/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.settings.SettingsKeys;
import ch.plaintext.settings.entity.Setting;
import ch.plaintext.settings.service.SettingsServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Karte 1063, Oberflaeche: der Geltungsbereich muss <b>waehlbar</b> und ein globaler Eintrag
 * <b>auffindbar</b> sein. Eine Einstellung, die fuer diesen Mandanten wirkt, aber in seiner Liste
 * nicht auftaucht, waere schlimmer als gar keine.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SettingsGlobalUiTest {

    @Mock private SettingsServiceImpl service;
    @Mock private PlaintextSecurity security;
    @InjectMocks private SettingsBackingBean bean;

    private static Setting setting(String key, String mandat) {
        Setting s = new Setting();
        s.setKey(key);
        s.setMandat(mandat);
        return s;
    }

    @Test
    @DisplayName("global steht in der Mandantenauswahl, und zwar vorn")
    void globalIstWaehlbar() {
        when(security.getAllMandate()).thenReturn(Set.of("guild42", "bit-zollikofen"));

        List<String> mandate = bean.getAllMandate();

        assertThat(mandate).first().isEqualTo(SettingsKeys.MANDAT_GLOBAL);
        assertThat(mandate).containsExactly("global", "bit-zollikofen", "guild42");
    }

    @Test
    @DisplayName("global taucht genau einmal auf, auch wenn es die Mandantenliste schon nennt")
    void keinDoppelterEintrag() {
        when(security.getAllMandate()).thenReturn(Set.of("guild42", "global"));

        assertThat(bean.getAllMandate()).containsExactly("global", "guild42");
    }

    @Test
    @DisplayName("die Liste zeigt eigene UND globale Eintraege")
    void listeZeigtBeides() {
        ReflectionTestUtils.setField(bean, "root", true);
        when(security.getMandat()).thenReturn("guild42");
        when(service.getAllSettings("guild42")).thenReturn(List.of(setting("mail.host", "guild42")));
        when(service.getAllSettings("global")).thenReturn(List.of(setting("app.ownhost", "global")));

        ReflectionTestUtils.invokeMethod(bean, "loadData");

        assertThat(bean.getSettings()).extracting(Setting::getKey)
                .containsExactly("mail.host", "app.ownhost");
        assertThat(bean.istGlobal(setting("app.ownhost", "global"))).isTrue();
        assertThat(bean.istGlobal(setting("mail.host", "guild42"))).isFalse();
        assertThat(bean.istGlobal(null)).isFalse();
    }

    @Test
    @DisplayName("beim Mandanten global wird die Liste nicht doppelt geladen")
    void globalerMandantLaedtNichtDoppelt() {
        ReflectionTestUtils.setField(bean, "root", true);
        when(security.getMandat()).thenReturn("global");
        when(service.getAllSettings("global")).thenReturn(List.of(setting("app.ownhost", "global")));

        ReflectionTestUtils.invokeMethod(bean, "loadData");

        assertThat(bean.getSettings()).hasSize(1);
    }
}

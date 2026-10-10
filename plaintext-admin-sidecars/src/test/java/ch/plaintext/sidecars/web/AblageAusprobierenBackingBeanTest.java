/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.ablagen.DateiAblagenRegister;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Karte 1440, Entscheid worker 10.10.2026: die Testseite ist nur für ROOT, die Bean prüft das selbst. */
class AblageAusprobierenBackingBeanTest {

    private static AblageAusprobierenBackingBean bean(String... rollen) {
        Set<String> r = Set.of(rollen);
        PlaintextSecurity security = mock(PlaintextSecurity.class);
        when(security.ifGranted(anyString())).thenAnswer(a -> r.contains(a.<String>getArgument(0).replaceFirst("^ROLE_", "")));
        when(security.getMandat()).thenReturn("default");
        DateiAblagenRegister register = mock(DateiAblagenRegister.class);
        when(register.namen()).thenReturn(List.of());
        AblageAusprobierenBackingBean b = new AblageAusprobierenBackingBean();
        ReflectionTestUtils.setField(b, "security", security);
        ReflectionTestUtils.setField(b, "register", register);
        return b;
    }

    @Test
    @DisplayName("ADMIN ohne ROOT: Seitenaufruf und Speichern werden abgewiesen (Negativ)")
    void adminAbgewiesen() {
        AblageAusprobierenBackingBean b = bean("ADMIN", "USER");
        assertThatThrownBy(b::seitenaufruf).isInstanceOf(SecurityException.class);
        assertThatThrownBy(b::speichern).isInstanceOf(SecurityException.class);
        assertThat(b.getAblage()).isNull();
    }

    @Test
    @DisplayName("ROOT: Seitenaufruf baut die Auswahl unter ablage-demo/<mandat> (Positiv)")
    void rootDarf() {
        AblageAusprobierenBackingBean b = bean("ROOT");
        b.seitenaufruf();
        assertThat(b.getAblage()).isNotNull();
        assertThat(AblageAusprobierenBackingBean.ROLLEN).containsExactly("ROOT");
    }
}

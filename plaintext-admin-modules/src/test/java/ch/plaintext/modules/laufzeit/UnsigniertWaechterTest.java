/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules.laufzeit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Karte 1610: unsignierte Module nie mit Profil prod, auch wenn prod erst aus einer Konfig-Datei kommt. */
class UnsigniertWaechterTest {

    @Test
    void prodMitUnsigniertenModulenBrichtAb() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        assertThatThrownBy(() -> UnsigniertWaechter.pruefen("plaintext-z-a:1.0.0", env))
                .hasMessageContaining("Profil prod: Start abgebrochen");
    }

    @Test
    void ohneProdOderOhneUnsignierteModuleLaeuft() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatCode(() -> UnsigniertWaechter.pruefen(null, prod)).doesNotThrowAnyException();
        assertThatCode(() -> UnsigniertWaechter.pruefen("plaintext-z-a:1.0.0", new MockEnvironment())).doesNotThrowAnyException();
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules.laufzeit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Karte 1515: die Auswahl der Ansicht kommt aus der aktuellen Konfig (nur Module der Registry, ohne Bibliotheken). */
class LaufzeitModuleBackingBeanTest {

    @Test
    void auswahlAusDerKonfig() {
        LaufzeitModuleService service = mock(LaufzeitModuleService.class);
        LaufzeitModuleService.RegistryModul k = new LaufzeitModuleService.RegistryModul("plaintext-z-kontakte", "modul",
                "2.1.0", "a".repeat(64), "1.775.0", List.of(), List.of(), List.of(), List.of());
        when(service.stand()).thenReturn(new LaufzeitModuleService.Stand("/data/module", null, List.of(k),
                List.of("plaintext-z-kontakte:2.1.0", "pdfbox:3.0.5 (Bibliothek)", "plaintext-z-weg:1.0.0"), null,
                List.of(), List.of(), List.of(), List.of()));
        LaufzeitModuleBackingBean bean = new LaufzeitModuleBackingBean();
        bean.setService(service);

        bean.load();

        assertThat(bean.getAuswahl()).containsExactly("plaintext-z-kontakte");
        assertThat(bean.getStand().verzeichnis()).isEqualTo("/data/module");
    }

    @Test
    void abgewiesenOhneStand() {
        LaufzeitModuleService service = mock(LaufzeitModuleService.class);
        when(service.stand()).thenThrow(new SecurityException("nur ROOT"));
        LaufzeitModuleBackingBean bean = new LaufzeitModuleBackingBean();
        bean.setService(service);

        bean.load();

        assertThat(bean.getStand()).isNull();
    }
}

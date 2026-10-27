/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules.laufzeit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Karte 1612: Ist eine Registry weg, bleibt die gespeicherte Version gewaehlt (nicht still die erste Option). */
class ModulAuswahlBackingBeanTest {

    @Test
    void gespeicherteVersionBleibtWaehlbarOhneAngebot() throws Exception {
        ModulAuswahlService service = mock(ModulAuswahlService.class);
        when(service.ansicht()).thenReturn(new ModulAuswahlService.Ansicht(Map.of(), List.of(
                new ModulAuswahlService.Modul("plaintext-z-a", List.of(
                        new ModulAuswahlService.Version("3.0.1", "lokal", false, null)), "3.0.0", "3.0.0")),
                Map.of("nextcloud", "antwortet 503"), null, List.of(), "2.811.1", true, null));
        ModulAuswahlBackingBean bean = new ModulAuswahlBackingBean();
        bean.setService(service);

        bean.neuLesen();

        ModulAuswahlBackingBean.Zeile z = bean.getZeilen().get(0);
        assertThat(z.getVersion()).isEqualTo("3.0.0");
        assertThat(z.getOptionen()).containsExactly("latest", "3.0.1", "3.0.0");
    }
}

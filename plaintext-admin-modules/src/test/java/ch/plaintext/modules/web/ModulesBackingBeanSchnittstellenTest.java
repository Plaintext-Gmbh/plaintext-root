/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules.web;

import ch.plaintext.modules.katalog.SchnittstellenKatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Karte 1422: Filter und Anzeigehilfen der Schnittstellen auf der Modulseite. */
class ModulesBackingBeanSchnittstellenTest {

    private static SchnittstellenKatalog.Schnittstelle s(String kurz, boolean annotiert, String art, String... module) {
        List<SchnittstellenKatalog.Umsetzer> u = java.util.Arrays.stream(module)
                .map(m -> new SchnittstellenKatalog.Umsetzer("b-" + m, "ch.x." + kurz + "Impl", m, null)).toList();
        return new SchnittstellenKatalog.Schnittstelle("m", "ch.x." + kurz, kurz, "Zweck.", List.of(), List.of(), u,
                annotiert, art, annotiert ? "NEU" : "", "", "", "im Modul", "interface");
    }

    private final List<SchnittstellenKatalog.Schnittstelle> alle = List.of(
            s("IFoto", true, "SCHNITTSTELLE", "plaintext-z-fotos", "plaintext-z-fotos"),
            s("IZeit", true, "DTO"),
            s("Alt", false, ""));

    @Test
    @DisplayName("Filter: alle, Dienste, DTOs, nicht markiert")
    void filter() {
        assertThat(ModulesBackingBean.filtere(alle, "")).hasSize(3);
        assertThat(ModulesBackingBean.filtere(alle, null)).hasSize(3);
        assertThat(ModulesBackingBean.filtere(alle, "SCHNITTSTELLE")).extracting(SchnittstellenKatalog.Schnittstelle::kurz).containsExactly("IFoto");
        assertThat(ModulesBackingBean.filtere(alle, "DTO")).extracting(SchnittstellenKatalog.Schnittstelle::kurz).containsExactly("IZeit");
        assertThat(ModulesBackingBean.filtere(alle, "OHNE")).extracting(SchnittstellenKatalog.Schnittstelle::kurz).containsExactly("Alt");
    }

    @Test
    @DisplayName("Anzeige: Module ohne Doppel, Strich ohne Umsetzer, Art- und Seiteneffekt-Farben")
    void anzeige() {
        ModulesBackingBean b = new ModulesBackingBean();
        assertThat(b.umgesetztIn(alle.get(0))).isEqualTo("plaintext-z-fotos");
        assertThat(b.umgesetztIn(alle.get(1))).isEqualTo("—");
        assertThat(b.artText(alle.get(0))).isEqualTo("Dienst");
        assertThat(b.artText(alle.get(2))).isEqualTo("nicht markiert");
        assertThat(b.seiteneffekteSchwere("AUSSEN")).isEqualTo("danger");
        assertThat(b.seiteneffekteSchwere(null)).isEqualTo("secondary");
        assertThat(b.getSchnittstellen()).as("ohne Katalog (nach Deserialisierung) leer statt NPE").isEmpty();
    }

    @Test
    @DisplayName("Karte 1438: Modulzeilen nach Ebene filtern, Badge-Text und -Farbe, ohne Analyse leeres Bild statt NPE")
    void ebene() {
        ch.plaintext.modules.ModuleView fotos = new ch.plaintext.modules.ModuleView("fotos", "Fotos", "1", true, "plaintext-z-fotos");
        ch.plaintext.modules.ModuleView cron = new ch.plaintext.modules.ModuleView("cron", "Cron", "1", true, "plaintext-admin-cron");
        List<ch.plaintext.modules.ModuleView> l = List.of(fotos, cron);
        assertThat(ModulesBackingBean.nachEbene(l, "root")).containsExactly(cron);
        assertThat(ModulesBackingBean.nachEbene(l, "modul")).containsExactly(fotos);
        assertThat(ModulesBackingBean.nachEbene(l, "")).containsExactly(fotos, cron);

        ModulesBackingBean b = new ModulesBackingBean();
        assertThat(b.ebeneText("root")).isEqualTo("root");
        assertThat(b.ebeneText("modul")).isEqualTo("Modul");
        assertThat(b.ebeneText(null)).isEmpty();
        assertThat(b.ebeneSchwere("modul")).isEqualTo("success");
        assertThat(b.ebeneSchwere("root")).isNull();
        assertThat(b.modulBild(fotos).bietetAn()).isEmpty();
        assertThat(b.ebeneVon("ch.x.IFoto")).isEmpty();
        assertThat(b.kurzVon("ch.x.IFoto")).isEqualTo("ch.x.IFoto");
    }
}

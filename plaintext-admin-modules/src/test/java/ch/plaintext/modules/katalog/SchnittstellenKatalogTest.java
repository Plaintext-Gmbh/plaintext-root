/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules.katalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Karte 1405: Kataloge vom Klassenpfad lesen, Umsetzer zuordnen, suchen. */
class SchnittstellenKatalogTest {

    /** Ein Vertrag, den der Test-Katalog beschreibt. */
    public interface Vertrag {
        long rechne(long betrag);
    }

    static class Umsetzung implements Vertrag {
        @Override
        public long rechne(long betrag) {
            return betrag;
        }
    }

    SchnittstellenKatalog katalog() {
        ApplicationContext k = mock(ApplicationContext.class);
        when(k.getBeanNamesForType(any(Class.class), anyBoolean(), anyBoolean())).thenAnswer(i ->
                i.getArgument(0) == Vertrag.class ? new String[]{"umsetzung"} : new String[0]);
        when(k.getType("umsetzung")).thenAnswer(i -> Umsetzung.class);
        return new SchnittstellenKatalog(k);
    }

    @Test
    @DisplayName("Positivkontrolle: Katalog gelesen, Methode und Umsetzer verknüpft, fehlende Klasse ohne Umsetzer")
    void lesen() {
        SchnittstellenKatalog k = katalog();
        assertThat(k.alle()).extracting(SchnittstellenKatalog.Schnittstelle::kurz).contains("Vertrag", "Weg");
        SchnittstellenKatalog.Schnittstelle v = k.eine("Vertrag").orElseThrow();
        assertThat(v.modul()).isEqualTo("test-interfaces");
        assertThat(v.zweckKurz()).isEqualTo("Rechnet Mails um.");
        assertThat(v.methoden().getFirst().parameter().getFirst().name()).isEqualTo("betrag");
        assertThat(v.umsetzer()).extracting(SchnittstellenKatalog.Umsetzer::bean, SchnittstellenKatalog.Umsetzer::klasse)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("umsetzung", Umsetzung.class.getName()));
        assertThat(k.eine("ch.plaintext.fehlt.Weg").orElseThrow().umsetzer()).isEmpty();
    }

    @Test
    @DisplayName("Suche in Name, Zweck und Methoden; Gegenprobe ohne Treffer")
    void suche() {
        SchnittstellenKatalog k = katalog();
        assertThat(k.suche("rechnet mails")).extracting(SchnittstellenKatalog.Schnittstelle::kurz).containsExactly("Vertrag");
        assertThat(k.suche("rechne")).extracting(SchnittstellenKatalog.Schnittstelle::kurz).contains("Vertrag");
        assertThat(k.suche("gibtesnirgends")).isEmpty();
    }

    @Test
    @DisplayName("Der echte Katalog von plaintext-root-interfaces liegt im Jar (Prozessor im Build)")
    void echterKatalog() {
        SchnittstellenKatalog.Schnittstelle s = katalog().eine("ch.plaintext.secrets.SecretResolver").orElseThrow();
        assertThat(s.modul()).isEqualTo("plaintext-root-interfaces");
        assertThat(s.zweck()).contains("secret");
        assertThat(s.methoden()).extracting(SchnittstellenKatalog.Methode::name).containsExactly("resolve");
        assertThat(katalog().alle().stream().filter(x -> x.modul().equals("plaintext-root-interfaces")).count()).isGreaterThan(30);
    }

    @Test
    @DisplayName("Binärname verschachtelter Schnittstellen und Modulname aus dem Jar")
    void hilfen() {
        assertThat(SchnittstellenKatalog.binaerName("ch.plaintext.a.Aussen.Innen")).isEqualTo("ch.plaintext.a.Aussen$Innen");
        assertThat(SchnittstellenKatalog.binaerName("ch.plaintext.a.Dienst")).isEqualTo("ch.plaintext.a.Dienst");
        assertThat(SchnittstellenKatalog.modul(org.junit.jupiter.api.Test.class)).isEqualTo("junit-jupiter-api");
    }
}

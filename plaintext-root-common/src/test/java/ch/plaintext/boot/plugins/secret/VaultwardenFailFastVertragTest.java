/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Haelt fest, dass eine {@code vault:}-Referenz im <b>echten</b> Boot-Aufbau entweder aufgeloest
 * ankommt oder den Start abbricht — aber niemals als Roh-String im Ziel-Feld landet.
 *
 * <h2>Warum der Aufbau hier {@code ConfigurationPropertySources.attach} enthaelt</h2>
 * <p>Genau daran ist der Mechanismus vorher gescheitert (Karte 868): {@link StandardEnvironment}
 * allein — wie im {@link VaultwardenPropertySourceTest} — ist <b>nicht</b> die Umgebung, in der die
 * Apps laufen. Spring Boot ruft bei jedem Start {@code attach(environment)} auf und haengt damit
 * eine Source {@code configurationProperties} VOR alle anderen, die Zugriffe selbst beantwortet.
 * Die lazy {@link VaultwardenPropertySource} wurde so umgangen und der unaufgeloeste Literal
 * {@code vault:<item>} landete im {@code @Value}-Feld — bei einem {@code String}-Feld voellig
 * unbemerkt, weil der Boot durchlief.</p>
 *
 * <p>Ein Test ohne {@code attach} war gruen und hat den Fehler trotzdem durchgelassen. Deshalb
 * gehoert das {@code attach} in den Aufbau, nicht in einen Kommentar.</p>
 */
class VaultwardenFailFastVertragTest {

    private static final String REFERENZ_OK = "vault:app.jira-bit-admin";
    private static final String REFERENZ_KAPUTT = "vault:app.gibtsnicht";
    private static final String SECRET = "streng-geheimer-wert-4711";

    @Configuration
    static class Bohne {
        @Value("${app.secret:}")
        String wert;

        @Bean
        static PropertySourcesPlaceholderConfigurer pspc() {
            return new PropertySourcesPlaceholderConfigurer();
        }
    }

    private VaultwardenSecretService service() {
        VaultwardenSecretService svc = mock(VaultwardenSecretService.class);
        when(svc.isEnabled()).thenReturn(true);
        when(svc.getPassword("app.jira-bit-admin")).thenReturn(Optional.of(SECRET));
        when(svc.getPassword("app.gibtsnicht")).thenReturn(Optional.empty());
        return svc;
    }

    /** Environment wie im Boot: Referenz in einer Source, lazy Source davor, danach {@code attach}. */
    private StandardEnvironment umgebung(String referenz) {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> map = new HashMap<>();
        map.put("app.secret", referenz);
        env.getPropertySources().addLast(new MapPropertySource("test", map));
        env.getPropertySources().addFirst(
                new VaultwardenPropertySource(env, new VaultwardenValueResolver(this::service)));
        return env;
    }

    private void aufloesen(StandardEnvironment env) {
        VaultwardenEagerResolution.resolveAll(env, new VaultwardenValueResolver(this::service));
    }

    // ── Positivkontrolle: der Wert kommt im @Value an ─────────────────────────

    @Test
    void aufloesbareReferenzLandetAlsKlartextImValueFeld() {
        StandardEnvironment env = umgebung(REFERENZ_OK);
        aufloesen(env);
        ConfigurationPropertySources.attach(env);

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.setEnvironment(env);
            ctx.register(Bohne.class);
            ctx.refresh();
            assertThat(ctx.getBean(Bohne.class).wert).isEqualTo(SECRET);
        }
    }

    // ── Die eigentliche Regression: NIE der Roh-String ────────────────────────

    @Test
    void unaufloesbareReferenzBrichtAbUndLandetNieAlsRohStringImFeld() {
        StandardEnvironment env = umgebung(REFERENZ_KAPUTT);

        // Der Abbruch muss hier fallen — nicht erst, wenn ihn ein Adapter verschluckt hat.
        assertThatThrownBy(() -> aufloesen(env))
                .isInstanceOf(VaultwardenPropertyResolutionException.class)
                .hasMessageContaining("app.secret")
                .hasMessageContaining("app.gibtsnicht")
                .hasMessageNotContaining(SECRET);
    }

    /**
     * Der Gegenbeweis zur Fehlerklasse: laesst man die Aufloesung weg, kommt im echten Aufbau
     * (mit {@code attach}) genau der Roh-String an. Dieser Test dokumentiert, was ohne
     * {@link VaultwardenEagerResolution} passieren wuerde — und wuerde rot, sobald jemand meint,
     * die lazy Source allein genuege.
     */
    @Test
    void ohneAufloesungWuerdeDerRohStringDurchkommen() {
        StandardEnvironment env = umgebung(REFERENZ_KAPUTT);
        ConfigurationPropertySources.attach(env);

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.setEnvironment(env);
            ctx.register(Bohne.class);
            ctx.refresh();
            assertThat(ctx.getBean(Bohne.class).wert)
                    .as("belegt die Fehlerklasse aus Karte 868: der Adapter reicht den Roh-Wert durch")
                    .isEqualTo(REFERENZ_KAPUTT);
        }
    }

    // ── Der Typ der Quell-Source traegt die Namens-Semantik ───────────────────

    @Test
    void referenzInDerUmgebungBleibtUeberDenKanonischenNamenErreichbar() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> envMap = new LinkedHashMap<>();
        envMap.put("APP_SECRET", REFERENZ_OK);
        env.getPropertySources().addLast(new SystemEnvironmentPropertySource("systemEnvironment", envMap));

        aufloesen(env);

        // Haette die Ersetzung den Typ verloren, waere der Wert unter diesem Namen unauffindbar.
        assertThat(env.getProperty("app.secret")).isEqualTo(SECRET);
    }
}

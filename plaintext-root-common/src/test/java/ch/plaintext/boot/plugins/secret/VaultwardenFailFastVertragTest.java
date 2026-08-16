/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;

/**
 * Haelt die Fail-fast-Zusage aus {@link VaultwardenEnvironmentPostProcessor} auf dem Weg fest, auf
 * dem sie im Betrieb tatsaechlich zaehlt: {@code @Value} ueber den
 * {@link PropertySourcesPlaceholderConfigurer}.
 *
 * <p><b>Warum zusaetzlich zu {@link VaultwardenPropertySourceTest}:</b> Jener Test prueft den
 * Abbruch auf Environment-Ebene ({@code environment.getProperty(...)}). Die Apps lesen ihre Secrets
 * aber ueber {@code @Value} — und fast alle mit einem <b>leeren Default</b>
 * ({@code ${…:}}), bei dem die naheliegende Erwartung „faellt eben auf den Default zurueck" waere.
 * Genau diese Erwartung waere fatal: ein leerer Wert sieht aus wie eine Konfigurationsluecke und
 * laesst die App mit einem Pseudo-Secret weiterlaufen. Die Luecke fiel bei Karte 868 auf.</p>
 */
class VaultwardenFailFastVertragTest {

    private static final String REFERENZ_OK = "vault:app.jira-bit-admin";
    private static final String REFERENZ_KAPUTT = "vault:app.gibtsnicht";
    private static final String SECRET = "streng-geheimer-wert-4711";

    // ── Testkonfigurationen: einmal mit, einmal ohne Default ──────────────────

    @Configuration
    static class MitLeeremDefault {
        @Value("${app.secret:}")
        String wert;

        @Bean
        static PropertySourcesPlaceholderConfigurer pspc() {
            return new PropertySourcesPlaceholderConfigurer();
        }
    }

    @Configuration
    static class OhneDefault {
        @Value("${app.secret}")
        String wert;

        @Bean
        static PropertySourcesPlaceholderConfigurer pspc() {
            return new PropertySourcesPlaceholderConfigurer();
        }
    }

    /** Context mit vorgeschalteter Vault-Source; {@code trefferImTresor} steuert Positiv/Negativ. */
    private AnnotationConfigApplicationContext context(Class<?> config, String referenz, boolean trefferImTresor) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();

        Map<String, Object> map = new HashMap<>();
        map.put("app.secret", referenz);
        ctx.getEnvironment().getPropertySources().addLast(new MapPropertySource("test", map));

        VaultwardenSecretService svc = mock(VaultwardenSecretService.class);
        when(svc.isEnabled()).thenReturn(true);
        when(svc.getPassword("app.jira-bit-admin"))
                .thenReturn(trefferImTresor ? Optional.of(SECRET) : Optional.empty());
        when(svc.getPassword("app.gibtsnicht")).thenReturn(Optional.empty());

        ctx.getEnvironment().getPropertySources().addFirst(
                new VaultwardenPropertySource(ctx.getEnvironment(), new VaultwardenValueResolver(() -> svc)));
        ctx.register(config);
        return ctx;
    }

    // ── Positivkontrolle ──────────────────────────────────────────────────────

    @Test
    void aufloesbareReferenzLandetImValueFeld() {
        try (AnnotationConfigApplicationContext ctx = context(MitLeeremDefault.class, REFERENZ_OK, true)) {
            ctx.refresh();
            assertThat(ctx.getBean(MitLeeremDefault.class).wert).isEqualTo(SECRET);
        }
    }

    // ── Negativprobe ohne Default ─────────────────────────────────────────────

    @Test
    void unaufloesbareReferenzBrichtDenContextAbOhneDefault() {
        try (AnnotationConfigApplicationContext ctx = context(OhneDefault.class, REFERENZ_KAPUTT, false)) {
            assertThatThrownBy(ctx::refresh)
                    .hasRootCauseInstanceOf(VaultwardenPropertyResolutionException.class)
                    .rootCause()
                    .hasMessageContaining("app.secret")
                    .hasMessageContaining("app.gibtsnicht");
        }
    }

    // ── Negativprobe MIT leerem Default — die eigentliche Zusage ──────────────

    @Test
    void unaufloesbareReferenzBrichtAuchMitLeeremDefaultAb() {
        try (AnnotationConfigApplicationContext ctx = context(MitLeeremDefault.class, REFERENZ_KAPUTT, false)) {
            // Der leere Default darf NICHT gewinnen: sonst startet die App mit "" statt mit dem
            // Secret und der Ausfall sieht wie eine harmlose Konfigurationsluecke aus.
            assertThatThrownBy(ctx::refresh)
                    .hasRootCauseInstanceOf(VaultwardenPropertyResolutionException.class)
                    .rootCause()
                    .hasMessageContaining("app.secret")
                    .hasMessageContaining("app.gibtsnicht");
        }
    }

    // ── Die Abbruchmeldung darf kein Secret enthalten ─────────────────────────

    @Test
    void abbruchmeldungNenntNamenAberNiemalsWerte() {
        // Der Tresor hat einen Treffer fuer app.jira-bit-admin, die Referenz zeigt aber ins Leere:
        // so ist ein Secret im Spiel, das in keiner Meldung auftauchen darf.
        try (AnnotationConfigApplicationContext ctx = context(MitLeeremDefault.class, REFERENZ_KAPUTT, true)) {
            assertThatThrownBy(ctx::refresh)
                    .rootCause()
                    .hasMessageNotContaining(SECRET);
        }
    }
}

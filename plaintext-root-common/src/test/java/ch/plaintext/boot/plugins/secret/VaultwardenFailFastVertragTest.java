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
 * Pins down that a {@code vault:} reference in the <b>real</b> boot setup either arrives
 * resolved or aborts the startup — but never ends up as a raw string in the target field.
 *
 * <h2>Why the setup here includes {@code ConfigurationPropertySources.attach}</h2>
 * <p>That is exactly where the mechanism failed before (Karte 868): {@link StandardEnvironment}
 * alone — as in {@link VaultwardenPropertySourceTest} — is <b>not</b> the environment the apps
 * run in. Spring Boot calls {@code attach(environment)} on every start and thereby hangs a
 * source {@code configurationProperties} IN FRONT of all others, which answers accesses itself.
 * The lazy {@link VaultwardenPropertySource} was bypassed that way and the unresolved literal
 * {@code vault:<item>} ended up in the {@code @Value} field — completely unnoticed with a
 * {@code String} field, because the boot ran through.</p>
 *
 * <p>A test without {@code attach} was green and let the bug through nonetheless. That is why
 * the {@code attach} belongs in the setup, not in a comment.</p>
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

    /** Environment as in the boot: reference in a source, lazy source in front, then {@code attach}. */
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

    // ── Positive control: the value arrives in the @Value ─────────────────────

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

    // ── The actual regression: NEVER the raw string ───────────────────────────

    @Test
    void unaufloesbareReferenzBrichtAbUndLandetNieAlsRohStringImFeld() {
        StandardEnvironment env = umgebung(REFERENZ_KAPUTT);

        // The abort has to happen here — not only once an adapter has swallowed it.
        assertThatThrownBy(() -> aufloesen(env))
                .isInstanceOf(VaultwardenPropertyResolutionException.class)
                .hasMessageContaining("app.secret")
                .hasMessageContaining("app.gibtsnicht")
                .hasMessageNotContaining(SECRET);
    }

    /**
     * The counter-proof for this class of bug: if the resolution is left out, exactly the raw
     * string arrives in the real setup (with {@code attach}). This test documents what would
     * happen without {@link VaultwardenEagerResolution} — and would go red as soon as somebody
     * thinks the lazy source alone is enough.
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

    // ── The type of the source carries the naming semantics ───────────────────

    @Test
    void referenzInDerUmgebungBleibtUeberDenKanonischenNamenErreichbar() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> envMap = new LinkedHashMap<>();
        envMap.put("APP_SECRET", REFERENZ_OK);
        env.getPropertySources().addLast(new SystemEnvironmentPropertySource("systemEnvironment", envMap));

        aufloesen(env);

        // Had the replacement lost the type, the value would be unfindable under this name.
        assertThat(env.getProperty("app.secret")).isEqualTo(SECRET);
    }
}

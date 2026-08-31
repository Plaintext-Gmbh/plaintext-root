/*
 * Plaintext GmbH
 */
package ch.plaintext.boot.plugins.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Checks the precedence against <b>real ConfigData</b> — that is, against the mechanism that
 * loads the {@code application.yml}.
 *
 * <h2>Why this test exists in addition</h2>
 * <p>{@link PlaintextDefaultsEnvironmentPostProcessorTest} checks the same thing against a
 * {@code MockEnvironment} — and was <b>green while the precedence on PROD was wrong</b>.
 * On 08.08.2026 {@code plaintext-root} lost its {@code Secure} cookie, because the processor
 * ran with {@code HIGHEST_PRECEDENCE} <em>before</em> the {@code ConfigDataEnvironmentPostProcessor}:
 * the source inserted with {@code addLast} thereby ended up <em>above</em> the
 * {@code application.yml} that was only inserted afterwards.
 *
 * <p>{@code MockEnvironment} does not model that ordering at all. The unit test could therefore
 * not find the bug — it checked the intention, not the behaviour. This test instead starts a
 * real application with a configuration file of its own and reads off which value actually wins.
 */
class PlaintextDefaultsPraezedenzTest {

    @Configuration(proxyBeanMethods = false)
    static class LeereAnwendung {
    }

    private ConfigurableEnvironment starteMit(String konfigName) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(LeereAnwendung.class)
                .web(WebApplicationType.NONE)
                .bannerMode(org.springframework.boot.Banner.Mode.OFF);
        if (konfigName != null) {
            builder.properties("spring.config.name=" + konfigName);
        }
        try (ConfigurableApplicationContext context = builder.run()) {
            // The environment survives the close — only the context is torn down.
            return context.getEnvironment();
        }
    }

    @Test
    @DisplayName("Eine anwendungseigene application.yml schlaegt die Grundeinstellungen")
    void anwendungsdateiGewinnt() {
        ConfigurableEnvironment environment = starteMit("test-eigene-app");

        // plaintext-defaults.yml says lax/false, test-eigene-app.yml says strict/true.
        // If lax wins here, the precedence is inverted — exactly the PROD bug of 08.08.2026.
        assertThat(environment.getProperty("server.servlet.session.cookie.same-site"))
                .as("Die Anwendung muss die Grundeinstellung ueberschreiben koennen")
                .isEqualTo("strict");
        assertThat(environment.getProperty("server.servlet.session.cookie.secure"))
                .as("Genau dieser Wert ging auf PROD verloren")
                .isEqualTo("true");
    }

    @Test
    @DisplayName("Ohne eigene Datei greifen die Grundeinstellungen")
    void ohneAnwendungsdateiGreifenDieDefaults() {
        ConfigurableEnvironment environment = starteMit("gibt-es-nicht");

        assertThat(environment.getProperty("server.servlet.session.cookie.same-site"))
                .as("Sonst waere der ganze Processor wirkungslos")
                .isEqualTo("lax");
        assertThat(environment.getProperty("server.servlet.session.tracking-modes"))
                .isEqualTo("cookie");
    }
}

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
 * Prueft die Praezedenz gegen <b>echtes ConfigData</b> — also gegen den Mechanismus, der die
 * {@code application.yml} laedt.
 *
 * <h2>Warum es diesen Test zusaetzlich gibt</h2>
 * <p>{@link PlaintextDefaultsEnvironmentPostProcessorTest} prueft dasselbe gegen ein
 * {@code MockEnvironment} — und war <b>gruen, waehrend die Praezedenz auf PROD falsch war</b>.
 * Am 08.08.2026 verlor {@code plaintext-root} sein {@code Secure}-Cookie, weil der Processor
 * mit {@code HIGHEST_PRECEDENCE} <em>vor</em> dem {@code ConfigDataEnvironmentPostProcessor}
 * lief: Die mit {@code addLast} eingefuegte Source landete damit <em>ueber</em> der erst danach
 * eingefuegten {@code application.yml}.
 *
 * <p>{@code MockEnvironment} bildet diese Reihenfolge gar nicht ab. Der Unit-Test konnte den
 * Fehler deshalb nicht finden — er hat die Absicht geprueft, nicht das Verhalten. Dieser Test
 * startet stattdessen eine echte Anwendung mit einer eigenen Konfigurationsdatei und liest ab,
 * welcher Wert tatsaechlich gewinnt.
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
            // Environment ueberlebt das Schliessen — nur der Kontext wird abgeraeumt.
            return context.getEnvironment();
        }
    }

    @Test
    @DisplayName("Eine anwendungseigene application.yml schlaegt die Grundeinstellungen")
    void anwendungsdateiGewinnt() {
        ConfigurableEnvironment environment = starteMit("test-eigene-app");

        // plaintext-defaults.yml sagt lax/false, test-eigene-app.yml sagt strict/true.
        // Gewinnt hier lax, ist die Praezedenz verdreht — genau der PROD-Fehler vom 08.08.2026.
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

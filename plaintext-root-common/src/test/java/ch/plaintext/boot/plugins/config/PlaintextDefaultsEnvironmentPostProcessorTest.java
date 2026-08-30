/*
 * Plaintext GmbH
 */
package ch.plaintext.boot.plugins.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

/**
 * Shows that the shared base settings arrive — and that they can still be
 * overridden.
 *
 * <p><b>What this test deliberately does NOT do:</b> check whether the keys <em>are present</em>
 * in {@code plaintext-defaults.yml}. Exactly that kind of test reported green for
 * {@code @EnableMethodSecurity} (Karte 546) while the annotation had no effect: presence is not
 * effectiveness. What is checked is therefore the resolved value in the
 * {@code Environment} — that is, what the application actually sees.
 */
class PlaintextDefaultsEnvironmentPostProcessorTest {

    private final PlaintextDefaultsEnvironmentPostProcessor processor =
            new PlaintextDefaultsEnvironmentPostProcessor();

    @Test
    @DisplayName("Die Grundeinstellungen sind nach dem Lauf im Environment sichtbar")
    void speistDefaultsEin() {
        MockEnvironment environment = new MockEnvironment();

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("server.servlet.session.cookie.same-site"))
                .as("SameSite fehlte in allen fuenf Consumern (Karte 620)")
                .isEqualTo("lax");
        assertThat(environment.getProperty("server.servlet.session.tracking-modes"))
                .as("URL-Tracking erzeugte ;jsessionid=-URLs (Karte 612)")
                .isEqualTo("cookie");
        assertThat(environment.getProperty("server.servlet.session.cookie.http-only"))
                .isEqualTo("true");
    }

    @Test
    @DisplayName("Secure ist per Voreinstellung aus — sonst sperrt es die INT-Instanzen aus")
    void secureIstVoreingestelltAus() {
        MockEnvironment environment = new MockEnvironment();

        processor.postProcessEnvironment(environment, new SpringApplication());

        // INT runs with the same profile as PROD; it is armed per container through
        // PLAINTEXT_COOKIE_SECURE. A true at this point would be an INT outage.
        assertThat(environment.getProperty("server.servlet.session.cookie.secure"))
                .isEqualTo("false");
    }

    @Test
    @DisplayName("Eine Anwendung kann jeden Wert weiterhin ueberschreiben")
    void anwendungGewinnt() {
        MockEnvironment environment = new MockEnvironment();
        // MockEnvironment puts its own source BEFORE ours — exactly like an application.yml.
        environment.setProperty("server.servlet.session.cookie.same-site", "strict");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("server.servlet.session.cookie.same-site"))
                .as("addLast darf die Anwendung nicht ueberstimmen — sonst waeren es Vorschriften")
                .isEqualTo("strict");
    }

    @Test
    @DisplayName("Swagger und OpenAPI-Docs sind per Voreinstellung aus")
    void springdocIstAus() {
        MockEnvironment environment = new MockEnvironment();

        processor.postProcessEnvironment(environment, new SpringApplication());

        // Karte 623: root has switched both off since Karte 314, the consumers have not -- their own
        // application.yml hid the root version. 38 resp. 11 open paths were measured.
        assertThat(environment.getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
    }

    @Test
    @DisplayName("Zweimal laufen aendert nichts")
    void istIdempotent() {
        MockEnvironment environment = new MockEnvironment();

        processor.postProcessEnvironment(environment, new SpringApplication());
        int nachEinmal = environment.getPropertySources().size();
        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getPropertySources().size()).isEqualTo(nachEinmal);
    }
}

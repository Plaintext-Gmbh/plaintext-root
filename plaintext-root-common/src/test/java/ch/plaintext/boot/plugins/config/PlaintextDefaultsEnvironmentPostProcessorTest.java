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
 * Belegt, dass die gemeinsamen Grundeinstellungen ankommen — und dass sie sich weiterhin
 * ueberschreiben lassen.
 *
 * <p><b>Was dieser Test absichtlich NICHT tut:</b> pruefen, ob die Schluessel in
 * {@code plaintext-defaults.yml} <em>stehen</em>. Genau diese Sorte Test hat bei
 * {@code @EnableMethodSecurity} (Karte 546) gruen gemeldet, waehrend die Annotation wirkungslos
 * war: Vorhandensein ist nicht Wirksamkeit. Geprueft wird deshalb der aufgeloeste Wert im
 * {@code Environment} — also das, was die Anwendung tatsaechlich sieht.
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

        // INT laeuft mit demselben Profil wie PROD; scharf geschaltet wird je Container ueber
        // PLAINTEXT_COOKIE_SECURE. Ein true an dieser Stelle waere ein INT-Ausfall.
        assertThat(environment.getProperty("server.servlet.session.cookie.secure"))
                .isEqualTo("false");
    }

    @Test
    @DisplayName("Eine Anwendung kann jeden Wert weiterhin ueberschreiben")
    void anwendungGewinnt() {
        MockEnvironment environment = new MockEnvironment();
        // MockEnvironment legt seine eigene Source VOR unsere — genau wie eine application.yml.
        environment.setProperty("server.servlet.session.cookie.same-site", "strict");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("server.servlet.session.cookie.same-site"))
                .as("addLast darf die Anwendung nicht ueberstimmen — sonst waeren es Vorschriften")
                .isEqualTo("strict");
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

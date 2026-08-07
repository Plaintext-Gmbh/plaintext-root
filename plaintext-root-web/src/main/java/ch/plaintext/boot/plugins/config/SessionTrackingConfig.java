/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.config;

import jakarta.servlet.SessionTrackingMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumSet;

/**
 * Fuehrt Sitzungen ausschliesslich ueber das Cookie — nie ueber die URL (Karte 612).
 *
 * <p><b>Warum programmatisch und nicht in {@code application.yml}:</b> Jede Anwendung bringt eine
 * eigene {@code application.yml} mit, und die verdeckt die aus {@code plaintext-root-webapp}.
 * Gemessen am 07.08.2026 am {@code Set-Cookie}-Header der laufenden Instanzen:
 *
 * <pre>
 *   root.plaintext.ch              JSESSIONID=...; Path=/; Secure; HttpOnly; SameSite=Lax
 *   app / guild / iot / schuetu    JSESSIONID=...; Path=/; HttpOnly
 * </pre>
 *
 * Die YAML-Einstellungen aus {@code plaintext-root-webapp} wirken also nur in {@code root} selbst.
 * Eine Einstellung, die dort steht, waere in vier von fuenf Anwendungen wirkungslos — und zwar
 * unsichtbar. Dieser Initializer haengt am Modul und wirkt darum ueberall, wo das Modul liegt.
 *
 * <p><b>Was das abstellt:</b> Ohne diese Festlegung haengt der Container die Sitzungskennung an
 * die URL, sobald ein Client keine Cookies mitschickt ({@code Location: /login.html;jsessionid=...},
 * am 07.08.2026 an allen Instanzen gemessen). Solche URLs landen in Lesezeichen, im Referer und in
 * Fehlermeldungen; eine davon ist als Zugang ohne Passwort und ohne zweiten Faktor in eine
 * Aufgabenkarte gelangt.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
@Configuration
public class SessionTrackingConfig {

    @Bean
    public ServletContextInitializer sessionTrackingModeInitializer() {
        return servletContext -> {
            servletContext.setSessionTrackingModes(EnumSet.of(SessionTrackingMode.COOKIE));
            log.info("Session-Tracking auf COOKIE festgelegt - keine Sitzungskennung in URLs");
        };
    }
}

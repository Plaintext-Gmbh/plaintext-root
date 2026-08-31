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
 * Tracks sessions exclusively through the cookie — never through the URL (Card 612).
 *
 * <p><b>Why programmatically and not in {@code application.yml}:</b> every application brings its
 * own {@code application.yml}, and that one shadows the file from {@code plaintext-root-webapp}.
 * Measured on 07.08.2026 on the {@code Set-Cookie} header of the running instances:
 *
 * <pre>
 *   root.plaintext.ch              JSESSIONID=...; Path=/; Secure; HttpOnly; SameSite=Lax
 *   app / guild / iot / schuetu    JSESSIONID=...; Path=/; HttpOnly
 * </pre>
 *
 * So the YAML settings from {@code plaintext-root-webapp} only take effect in {@code root} itself.
 * A setting placed there would have no effect in four out of five applications — and it would fail
 * invisibly. This initializer is attached to the module and therefore takes effect everywhere the
 * module is used.
 *
 * <p><b>What this prevents:</b> without this setting the container appends the session id to the
 * URL as soon as a client sends no cookies ({@code Location: /login.html;jsessionid=...}, measured
 * on 07.08.2026 on all instances). Such URLs end up in bookmarks, in the referer and in error
 * messages; one of them made it into a task card as access without a password and without a second
 * factor.
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

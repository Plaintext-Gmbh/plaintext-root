/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.ajax;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * {@link AuthenticationEntryPoint}, der einen JSF-/PrimeFaces-Ajax-Request mit einer gueltigen
 * XML-{@code partial-response} samt {@code <redirect>} beantwortet (Karte 385). Alle anderen
 * Requests gehen an den regulaeren Entry-Point (Form-Login-Redirect) weiter.
 *
 * <p>Ohne das erhaelt die Ajax-Engine bei abgelaufener Session einen HTML-Redirect auf die
 * Login-Seite bzw. einen JSON-Fehler und kann beides nicht verarbeiten — der Ladeindikator
 * dreht endlos.</p>
 */
@Slf4j
public class JsfAjaxAwareAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final AuthenticationEntryPoint delegate;
    private final String loginUrl;

    public JsfAjaxAwareAuthenticationEntryPoint(AuthenticationEntryPoint delegate, String loginUrl) {
        this.delegate = delegate;
        this.loginUrl = loginUrl;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException, ServletException {
        if (JsfAjaxResponses.isJsfAjaxRequest(request)) {
            // LOGGING (Karte 385, Manager-Review): siehe JsfAjaxAwareAccessDeniedHandler. Hier
            // bewusst INFO statt WARN: dieser Zweig ist der Normalfall einer abgelaufenen bzw.
            // nach einem Blue/Green-Deploy verlorenen Session. Als WARN wuerde er das Log bei
            // jedem Deploy fluten und die echten CSRF-Ablehnungen darin unsichtbar machen — genau
            // den Effekt, den dieser Fix beseitigen soll. Die Sichtbarkeit bleibt erhalten, die
            // Dringlichkeit ist eine andere.
            // Bewusst NICHT geloggt: Token, Session-Id, Benutzername, Request-Parameter.
            log.info("Ajax-Request ohne gueltige Authentifizierung (Session abgelaufen): {} {} "
                            + "— beantwortet mit JSF-partial-response, Redirect auf {}",
                    request.getMethod(), request.getRequestURI(), loginUrl);
            JsfAjaxResponses.sendPartialRedirect(response, request.getContextPath() + loginUrl);
            return;
        }
        delegate.commence(request, response, authException);
    }
}

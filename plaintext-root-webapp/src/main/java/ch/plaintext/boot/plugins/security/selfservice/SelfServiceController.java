/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.selfservice;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Public-facing endpoints for self-registration and password-reset flows.
 *
 * <p>Renders minimal inline HTML — the framework has no Thymeleaf and these
 * pages are visited at most a handful of times per user, so a dedicated
 * template engine would be overkill. The HTML borrows the same fonts/colours
 * as the maintenance page so the look stays consistent across the
 * unauthenticated flows.
 *
 * <p>All user input is HTML-escaped before rendering so a stale token in the
 * URL or a typoed e-mail cannot reflect script back into the page.
 */
@Controller
@RequiredArgsConstructor
public class SelfServiceController {

    private static final String DEFAULT_MANDAT = "default";

    private final RegistrationService registrationService;
    private final PasswordResetService passwordResetService;
    private final SelfServiceProperties properties;

    // ---------- Registration ----------

    @GetMapping(value = "/register", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> registerForm(HttpServletRequest request) {
        return html(page("Registrieren", """
                <form method="post" action="/register" class="card">
                  <h1>Konto anlegen</h1>
                  %s
                  <label>E-Mail-Adresse
                    <input type="email" name="email" required autofocus/>
                  </label>
                  <button type="submit">Verifikations-E-Mail senden</button>
                  <p class="subtle"><a href="/login.html">Zur Anmeldung</a></p>
                </form>
                """.formatted(csrfField(request))));
    }

    @PostMapping(value = "/register", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> registerSubmit(@RequestParam("email") String email,
                                                 HttpServletRequest request) {
        registrationService.startRegistration(email, mandat(), baseUrl(request));
        return html(page("Registrieren", """
                <div class="card">
                  <h1>Prüfen Sie Ihren Posteingang</h1>
                  <p>Wir haben einen Verifikationslink an die angegebene Adresse
                  geschickt — sofern dort ein Konto eröffnet werden darf.</p>
                  <p class="subtle"><a href="/login.html">Zur Anmeldung</a></p>
                </div>
                """));
    }

    @GetMapping(value = "/register/verify", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> registerVerifyForm(@RequestParam("token") String token,
                                                     HttpServletRequest request) {
        return html(page("Passwort setzen", """
                <form method="post" action="/register/verify" class="card">
                  <h1>Passwort wählen</h1>
                  %s
                  <input type="hidden" name="token" value="%s"/>
                  <label>Neues Passwort (mindestens 8 Zeichen)
                    <input type="password" name="password" minlength="8" required autofocus/>
                  </label>
                  <button type="submit">Konto anlegen</button>
                </form>
                """.formatted(csrfField(request), escape(token))));
    }

    @PostMapping(value = "/register/verify", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> registerVerifySubmit(@RequestParam("token") String token,
                                                       @RequestParam("password") String password) {
        RegistrationService.RegistrationResult result =
                registrationService.completeRegistration(token, password);
        if (!result.ok()) {
            return html(page("Fehler", """
                    <div class="card">
                      <h1>Link ungültig</h1>
                      <p>Der Verifikationslink ist abgelaufen oder bereits verbraucht.
                      Bitte starten Sie die Registrierung neu.</p>
                      <p class="subtle"><a href="/register">Erneut registrieren</a></p>
                    </div>
                    """));
        }
        return html(page("Konto angelegt", """
                <div class="card">
                  <h1>Willkommen!</h1>
                  <p>Ihr Konto wurde angelegt. Sie können sich jetzt anmelden.</p>
                  <p><a href="/login.html">Zur Anmeldung</a></p>
                </div>
                """));
    }

    // ---------- Password reset ----------

    @GetMapping(value = "/password-reset", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> resetForm(HttpServletRequest request) {
        return html(page("Passwort zurücksetzen", """
                <form method="post" action="/password-reset" class="card">
                  <h1>Passwort zurücksetzen</h1>
                  %s
                  <label>Benutzername / E-Mail
                    <input type="text" name="username" required autofocus/>
                  </label>
                  <button type="submit">Reset-Link senden</button>
                  <p class="subtle"><a href="/login.html">Zur Anmeldung</a></p>
                </form>
                """.formatted(csrfField(request))));
    }

    @PostMapping(value = "/password-reset", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> resetSubmit(@RequestParam("username") String username,
                                              HttpServletRequest request) {
        passwordResetService.startReset(username, mandat(), baseUrl(request));
        return html(page("Passwort zurücksetzen", """
                <div class="card">
                  <h1>Prüfen Sie Ihren Posteingang</h1>
                  <p>Wir haben einen Reset-Link an die hinterlegte Adresse geschickt —
                  sofern es ein passendes Konto gibt.</p>
                  <p class="subtle"><a href="/login.html">Zur Anmeldung</a></p>
                </div>
                """));
    }

    @GetMapping(value = "/password-reset/confirm", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> resetConfirmForm(@RequestParam("token") String token,
                                                   HttpServletRequest request) {
        return html(page("Neues Passwort", """
                <form method="post" action="/password-reset/confirm" class="card">
                  <h1>Neues Passwort wählen</h1>
                  %s
                  <input type="hidden" name="token" value="%s"/>
                  <label>Neues Passwort (mindestens 8 Zeichen)
                    <input type="password" name="password" minlength="8" required autofocus/>
                  </label>
                  <button type="submit">Passwort speichern</button>
                </form>
                """.formatted(csrfField(request), escape(token))));
    }

    @PostMapping(value = "/password-reset/confirm", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> resetConfirmSubmit(@RequestParam("token") String token,
                                                     @RequestParam("password") String password) {
        PasswordResetService.ResetResult result =
                passwordResetService.completeReset(token, password);
        if (!result.ok()) {
            return html(page("Fehler", """
                    <div class="card">
                      <h1>Link ungültig</h1>
                      <p>Der Reset-Link ist abgelaufen oder bereits verbraucht.
                      Bitte fordern Sie einen neuen an.</p>
                      <p class="subtle"><a href="/password-reset">Neuen Link anfordern</a></p>
                    </div>
                    """));
        }
        return html(page("Passwort gespeichert", """
                <div class="card">
                  <h1>Erledigt</h1>
                  <p>Ihr Passwort wurde aktualisiert. Sie können sich jetzt anmelden.</p>
                  <p><a href="/login.html">Zur Anmeldung</a></p>
                </div>
                """));
    }

    // ---------- Helpers ----------

    /**
     * Verstecktes CSRF-Eingabefeld für die HTML-POST-Formulare. Ohne dieses Feld weist Spring Security
     * den POST mit 403 ab (die Self-Service-Pfade sind permitAll, aber NICHT CSRF-befreit) — der Browser
     * landet dann auf einer Fehler-/Login-Seite statt auf der Bestätigung.
     */
    private static String csrfField(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) {
            return "";
        }
        return "<input type=\"hidden\" name=\"" + escape(token.getParameterName())
                + "\" value=\"" + escape(token.getToken()) + "\"/>";
    }

    private String mandat() {
        return properties.getDefaultMandat();
    }

    private static String baseUrl(HttpServletRequest request) {
        StringBuilder sb = new StringBuilder()
                .append(request.getScheme()).append("://")
                .append(request.getServerName());
        int port = request.getServerPort();
        if (("http".equals(request.getScheme()) && port != 80)
                || ("https".equals(request.getScheme()) && port != 443)) {
            sb.append(":").append(port);
        }
        return sb.toString();
    }

    private static ResponseEntity<String> html(String body) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(body);
    }

    private static String escape(String s) {
        // org.springframework.web.util.HtmlUtils.htmlEscape wird von CodeQL als XSS-Sanitizer erkannt
        // (und ist für den HTML-Kontext korrekt). Ersetzt die handgeschriebene Variante, die dieselben
        // Meta-Zeichen neutralisierte, aber vom Code-Scanning nicht als Sanitizer modelliert wurde
        // (java/xss High-Alert auf der html()-Ausgabe).
        return s == null ? "" : org.springframework.web.util.HtmlUtils.htmlEscape(s);
    }

    private static String page(String title, String body) {
        return """
                <!DOCTYPE html>
                <html lang="de">
                <head>
                    <meta charset="UTF-8">
                    <title>%s &middot; Plaintext</title>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
                    <style>
                        :root { color-scheme: light dark; }
                        * { box-sizing: border-box; }
                        body {
                            margin: 0;
                            min-height: 100vh;
                            display: flex;
                            align-items: center;
                            justify-content: center;
                            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI",
                                         Roboto, "Helvetica Neue", Arial, sans-serif;
                            background: linear-gradient(135deg, #f8fbff 0%%, #e8f4fd 100%%);
                            color: #333;
                        }
                        @media (prefers-color-scheme: dark) {
                            body { background: linear-gradient(135deg, #1a1a2e 0%%, #16213e 100%%); color: #e0e0e0; }
                            .card { background: #2a2b3d; border-color: #3c3d4f; }
                            input { background: #1a1a2e; color: #e0e0e0; border-color: #3c3d4f; }
                        }
                        .card {
                            background: #fff;
                            border: 1px solid #dee2e6;
                            border-radius: 12px;
                            padding: 2.5rem 2rem;
                            max-width: 26rem;
                            width: 90%%;
                            box-shadow: 0 10px 30px rgba(0, 0, 0, 0.08);
                        }
                        h1 { margin: 0 0 1rem 0; font-size: 1.4rem; }
                        label { display: block; margin: 1rem 0 0.25rem; font-size: 0.9rem; }
                        input { width: 100%%; padding: 0.6rem 0.75rem; font-size: 1rem;
                                border: 1px solid #ced4da; border-radius: 6px; }
                        button { width: 100%%; margin-top: 1.25rem; padding: 0.75rem;
                                 background: #4183c4; color: white; border: 0;
                                 border-radius: 6px; font-size: 1rem; cursor: pointer; }
                        button:hover { background: #3170b0; }
                        p { margin: 0.75rem 0; line-height: 1.45; }
                        .subtle { color: #6c757d; font-size: 0.9rem; text-align: center; }
                        a { color: #4183c4; text-decoration: none; }
                        a:hover { text-decoration: underline; }
                    </style>
                </head>
                <body>
                    %s
                </body>
                </html>
                """.formatted(escape(title), body);
    }
}

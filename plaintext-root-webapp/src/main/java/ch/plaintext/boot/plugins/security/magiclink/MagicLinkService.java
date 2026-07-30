/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.magiclink;

import ch.plaintext.SystemMailSender;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.mailtemplate.IMailTemplateProvider;
import ch.plaintext.mailtemplate.IMailTemplateProvider.RenderedMail;
import ch.plaintext.settings.ISetupConfigService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest;
import org.springframework.security.authentication.ott.OneTimeToken;
import org.springframework.security.authentication.ott.OneTimeTokenService;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Baut und versendet Magic-Links per E-Mail. Kapselt die zuvor im
 * {@link MagicLinkGenerationSuccessHandler} liegende Link-Bau- und Mail-Versand-Logik, damit sie
 * auch aus den Self-Service-/Admin-Formularen wiederverwendet werden kann.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MagicLinkService {

    private final OneTimeTokenService oneTimeTokenService;
    private final MyUserRepository userRepository;
    private final ObjectProvider<SystemMailSender> systemMailSenderProvider;
    private final ISetupConfigService setupConfigService;
    private final MagicLinkProperties properties;
    private final IMailTemplateProvider mailTemplateProvider;

    /**
     * Versendet den fertigen Magic-Link per System-Mail an den User.
     *
     * @return {@code true}, wenn die Mail tatsaechlich versendet wurde, sonst {@code false}
     * (kein Sender, kein Systemmailkonto, Versand fehlgeschlagen).
     */
    public boolean sendMagicLinkEmail(MyUserEntity user, String link) {
        String mandat = user.getMandat();
        String to = user.getUsername();
        SystemMailSender sender = systemMailSenderProvider.getIfAvailable();
        if (sender == null) {
            log.warn("MagicLink: Mail nicht gesendet (kein SystemMailSender / Mailbox nicht vorhanden), Mandat={}", mandat);
            return false;
        }
        Long accountId = setupConfigService.getSystemMailAccountId();
        if (accountId == null) {
            log.warn("MagicLink: Mail nicht gesendet, kein GLOBAL-Systemmailkonto konfiguriert (Mandat={})", mandat);
            return false;
        }
        String defaultBetreff = "Ihr Anmelde-Link";
        String defaultBody = """
                Hallo,

                jemand hat einen Anmelde-Link fuer dieses Konto angefordert.
                Klicken Sie auf den folgenden Link, um sich anzumelden (gueltig fuer {minuten} Minuten):

                {link}

                Falls Sie das nicht waren, koennen Sie diese Nachricht ignorieren.
                Ihr Konto bleibt unveraendert.
                """;
        RenderedMail mail = mailTemplateProvider.render(mandat, "auth.magic-link", defaultBetreff, defaultBody,
                Map.of("minuten", String.valueOf(properties.getTokenTtl().toMinutes()), "link", link));
        try {
            if (sender.sendSystemMail(accountId, to, mail.betreff(), mail.body(), false)) {
                log.info("MagicLink: Mail versendet fuer '{}' (Mandat: {})", to, mandat);
                return true;
            } else {
                log.warn("MagicLink: Mail konnte nicht versendet werden fuer '{}'", to);
                return false;
            }
        } catch (Exception e) {
            log.warn("MagicLink: Mail-Versand fehlgeschlagen fuer '{}': {}", to, e.getMessage());
            return false;
        }
    }

    /**
     * Baut den Magic-Link fuer ein bereits generiertes Token und versendet ihn per Mail.
     */
    public boolean sendForExistingToken(MyUserEntity user, String rawToken, HttpServletRequest request) {
        String link = buildMagicLink(request, rawToken);
        return sendMagicLinkEmail(user, link);
    }

    /**
     * Generiert ein neues Einmal-Token fuer den User und versendet den Magic-Link per Mail.
     * Bei unbekanntem User, fehlendem Mandat oder deaktiviertem Feature wird nichts generiert/versendet.
     */
    public boolean generateAndSend(String username, HttpServletRequest request) {
        MyUserEntity user = userRepository.findByUsername(username);
        if (user == null || user.getMandat() == null || !setupConfigService.isMagicLinkEnabled(user.getMandat())) {
            log.debug("MagicLink: generateAndSend abgelehnt fuer '{}' (unbekannter User / kein Mandat / Feature deaktiviert)", username);
            return false;
        }
        OneTimeToken ott = oneTimeTokenService.generate(new GenerateOneTimeTokenRequest(username));
        return sendForExistingToken(user, ott.getTokenValue(), request);
    }

    private String buildMagicLink(HttpServletRequest request, String token) {
        String base = properties.getPublicBaseUrl().isBlank()
                ? extractBaseUrl(request)
                : properties.getPublicBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + request.getContextPath() + "/login/ott?token=" + token;
    }

    /**
     * Fallback-Basis-URL, wenn {@code plaintext.magiclink.public-base-url} nicht konfiguriert ist.
     * Verwendet bewusst KEINE {@code X-Forwarded-*}-Header: die sind vom Client setzbar und
     * erlaubten sonst Phishing-Links mit echtem Token auf fremde Hosts (Host-Header-Injection).
     */
    private static String extractBaseUrl(HttpServletRequest request) {
        log.warn("MagicLink: plaintext.magiclink.public-base-url ist nicht konfiguriert – "
                + "Fallback auf Server-Namen '{}' (keine Forwarded-Header). "
                + "Bitte public-base-url konfigurieren, damit Links hinter dem Reverse-Proxy stimmen.",
                request.getServerName());
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }
}

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
 * Builds and sends magic links by e-mail. Encapsulates the link-building and mail-sending logic
 * that previously sat in the {@link MagicLinkGenerationSuccessHandler}, so that it
 * can also be reused from the self-service/admin forms.
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
     * Sends the finished magic link to the user via system mail.
     *
     * @return {@code true} if the mail was actually sent, otherwise {@code false}
     * (no sender, no system mail account, sending failed).
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
     * Builds the magic link for an already generated token and sends it by mail.
     */
    public boolean sendForExistingToken(MyUserEntity user, String rawToken, HttpServletRequest request) {
        String link = buildMagicLink(request, rawToken);
        return sendMagicLinkEmail(user, link);
    }

    /**
     * Generates a new one-time token for the user and sends the magic link by mail.
     * For an unknown user, a missing tenant or a deactivated feature nothing is generated/sent.
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
     * Fallback base URL when {@code plaintext.magiclink.public-base-url} is not configured.
     * Deliberately uses NO {@code X-Forwarded-*} headers: those can be set by the client and
     * would otherwise allow phishing links with a real token pointing to foreign hosts (host header injection).
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

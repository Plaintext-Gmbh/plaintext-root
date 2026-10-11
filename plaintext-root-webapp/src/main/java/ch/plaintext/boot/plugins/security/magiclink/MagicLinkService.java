/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.magiclink;

import ch.plaintext.SystemMailSender;
import ch.plaintext.boot.plugins.log.Log;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.framework.EigeneAdresse;
import ch.plaintext.mailtemplate.IMailTemplateProvider;
import ch.plaintext.mailtemplate.IMailTemplateProvider.RenderedMail;
import ch.plaintext.settings.ISetupConfigService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
    private final EigeneAdresse eigeneAdresse;

    /** Last fallback of {@link EigeneAdresse}, the same one {@code SelfServiceController.basisUrl} passes. */
    @Value("${plaintext.baseurl:}")
    private String plaintextBaseurl;

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
                log.info("MagicLink: Mail versendet fuer '{}' (Mandat: {})", Log.mail(to), mandat);
                return true;
            } else {
                log.warn("MagicLink: Mail konnte nicht versendet werden fuer '{}'", Log.mail(to));
                return false;
            }
        } catch (Exception e) {
            log.warn("MagicLink: Mail-Versand fehlgeschlagen fuer '{}': {}", Log.mail(to), e.getMessage());
            return false;
        }
    }

    /**
     * Builds the magic link for an already generated token and sends it by mail.
     * Without a configured own address nothing is sent: a login link must never point to a host
     * the request named (Karte 1501, S-6).
     */
    public boolean sendForExistingToken(MyUserEntity user, String rawToken, HttpServletRequest request) {
        String base = basisUrl();
        if (base.isBlank()) {
            log.warn("MagicLink: Mail nicht gesendet, keine eigene Adresse konfiguriert "
                    + "(plaintext.magiclink.public-base-url, app.ownhost, plaintext.app.ownhost oder plaintext.baseurl). "
                    + "Aus dem Request wird sie bewusst NICHT abgeleitet (Karte 1501).");
            return false;
        }
        return sendMagicLinkEmail(user, base + request.getContextPath() + "/login/ott?token=" + rawToken);
    }

    /**
     * Generates a new one-time token for the user and sends the magic link by mail.
     * For an unknown user, a missing tenant or a deactivated feature nothing is generated/sent.
     */
    public boolean generateAndSend(String username, HttpServletRequest request) {
        MyUserEntity user = userRepository.findByUsername(username);
        if (user == null || user.getMandat() == null || !setupConfigService.isMagicLinkEnabled(user.getMandat())) {
            log.debug("MagicLink: generateAndSend abgelehnt fuer '{}' (unbekannter User / kein Mandat / Feature deaktiviert)", Log.mail(username));
            return false;
        }
        OneTimeToken ott = oneTimeTokenService.generate(new GenerateOneTimeTokenRequest(username));
        return sendForExistingToken(user, ott.getTokenValue(), request);
    }

    /**
     * Base of the link — from configuration only, never from the request. Behind the
     * {@code ForwardedHeaderFilter} the request host is whatever {@code X-Forwarded-Host} says.
     * {@code public-base-url} stays the first choice, then the address every other outgoing link uses.
     */
    private String basisUrl() {
        String base = properties.getPublicBaseUrl();
        if (base == null || base.isBlank()) {
            base = eigeneAdresse.basis(plaintextBaseurl == null ? "" : plaintextBaseurl);
        }
        return base == null ? "" : EigeneAdresse.ohneEndSlash(base);
    }
}

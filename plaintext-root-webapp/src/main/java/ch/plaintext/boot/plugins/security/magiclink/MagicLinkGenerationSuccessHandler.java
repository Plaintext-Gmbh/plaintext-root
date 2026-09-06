/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.magiclink;

import ch.plaintext.boot.plugins.log.Log;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.settings.ISetupConfigService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.ott.OneTimeToken;
import org.springframework.security.web.authentication.ott.OneTimeTokenGenerationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Sends the magic link by e-mail instead of writing it into the response.
 * Always answers neutrally (redirect to login?magic_link_sent=true),
 * regardless of whether the mail was actually sent - no user enumeration.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MagicLinkGenerationSuccessHandler implements OneTimeTokenGenerationSuccessHandler {

    private final MyUserRepository userRepository;
    private final ISetupConfigService setupConfigService;
    private final MagicLinkService magicLinkService;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       OneTimeToken oneTimeToken) throws IOException, ServletException {
        String username = oneTimeToken.getUsername();

        MyUserEntity user = userRepository.findByUsername(username);
        if (user != null && setupConfigService.isMagicLinkEnabled(user.getMandat())) {
            magicLinkService.sendForExistingToken(user, oneTimeToken.getTokenValue(), request);
        } else if (user != null) {
            log.debug("MagicLink: fuer Mandat '{}' deaktiviert, Mail nicht gesendet", user.getMandat());
        } else {
            log.debug("MagicLink: kein User fuer '{}' gefunden, Mail nicht gesendet", Log.mail(username));
        }

        // Always the same neutral response
        response.sendRedirect(request.getContextPath() + "/login.xhtml?magic_link_sent=true");
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.deeplink;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;

/**
 * Entry point for deep links coming from mails (card 345): {@code /deeplink?type=…&mandat=…&id=…}.
 *
 * <p>The path deliberately stands in the {@code permitAll} list — not because it were open, but
 * because the <b>unauthenticated</b> case has to be handled cleanly here (see
 * {@link DeepLinkPendingStore}). The access protection sits one level deeper: without
 * authentication the call leads exclusively to the login page, every substantive check is done by
 * {@link DeepLinkResolver} and that only runs after the login.
 *
 * <p>GET only, no state change apart from switching the tenant; hence no CSRF topic.
 */
@Controller
@Slf4j
public class DeepLinkController {

    /** Target on every rejection — the same page that the page guard uses as well. */
    static final String ACCESS_DENIED_PAGE = "/access-denied.html";

    static final String LOGIN_PAGE = "/login.html";

    private final DeepLinkResolver resolver;

    public DeepLinkController(DeepLinkResolver resolver) {
        this.resolver = resolver;
    }

    @GetMapping(DeepLinkService.DEEPLINK_PATH)
    public void oeffne(@RequestParam(name = "type", required = false) String type,
                       @RequestParam(name = "mandat", required = false) String mandat,
                       @RequestParam(name = "id", required = false) String id,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {

        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();

        if (!istAngemeldet()) {
            // Remember the target in the session (only the three validated identifiers, no URL) and send
            // the user to the login. After a successful login the
            // PlaintextAuthenticationSuccessHandler brings the deep link back out and calls this
            // endpoint again — including all checks.
            DeepLinkPendingStore.merke(request, type, mandat, id);
            log.debug("Deep-Link ohne Anmeldung aufgerufen -> Login, Ziel gemerkt");
            response.sendRedirect(contextPath + LOGIN_PAGE);
            return;
        }

        DeepLinkResolution resolution = resolver.resolve(type, mandat, id);
        if (!resolution.erlaubt()) {
            // Deliberately without details in the URL: the reason belongs in the log, not on the page.
            response.sendRedirect(contextPath + ACCESS_DENIED_PAGE);
            return;
        }
        response.sendRedirect(contextPath + resolution.zielPfad());
    }

    /**
     * Remember-me counts as authenticated here (the user has a real identity and therefore real
     * permissions); anonymous does not.
     */
    private boolean istAngemeldet() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }
}

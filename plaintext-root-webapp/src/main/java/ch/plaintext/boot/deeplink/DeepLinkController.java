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
 * Einstiegspunkt fuer Deep-Links aus Mails (Karte 345): {@code /deeplink?type=…&mandat=…&id=…}.
 *
 * <p>Der Pfad steht bewusst in der {@code permitAll}-Liste — nicht, weil er offen waere, sondern
 * weil der <b>nicht angemeldete</b> Fall hier sauber behandelt werden muss (siehe
 * {@link DeepLinkPendingStore}). Der Zugriffsschutz sitzt eine Ebene tiefer: ohne
 * Authentifizierung fuehrt der Aufruf ausschliesslich zur Login-Seite, jede inhaltliche Pruefung
 * macht {@link DeepLinkResolver} und die laeuft erst nach dem Login.
 *
 * <p>Nur GET, keine Zustandsaenderung ausser dem Mandat-Wechsel; darum kein CSRF-Thema.
 */
@Controller
@Slf4j
public class DeepLinkController {

    /** Ziel bei jeder Ablehnung — dieselbe Seite, die auch der Seiten-Guard verwendet. */
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
            // Ziel in der Session merken (nur die drei validierten Bezeichner, keine URL) und zur
            // Anmeldung schicken. Nach erfolgreichem Login holt der
            // PlaintextAuthenticationSuccessHandler den Deep-Link wieder hervor und ruft diesen
            // Endpunkt erneut auf — inklusive aller Pruefungen.
            DeepLinkPendingStore.merke(request, type, mandat, id);
            log.debug("Deep-Link ohne Anmeldung aufgerufen -> Login, Ziel gemerkt");
            response.sendRedirect(contextPath + LOGIN_PAGE);
            return;
        }

        DeepLinkResolution resolution = resolver.resolve(type, mandat, id);
        if (!resolution.erlaubt()) {
            // Bewusst ohne Details in der URL: der Grund steht im Log, nicht auf der Seite.
            response.sendRedirect(contextPath + ACCESS_DENIED_PAGE);
            return;
        }
        response.sendRedirect(contextPath + resolution.zielPfad());
    }

    /**
     * Remember-Me zaehlt hier als angemeldet (der Benutzer hat eine echte Identitaet und damit
     * echte Rechte); Anonymous nicht.
     */
    private boolean istAngemeldet() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }
}

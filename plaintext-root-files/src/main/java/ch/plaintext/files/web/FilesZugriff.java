/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.files.web;

import ch.plaintext.boot.security.PageAccessGuardService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Wer darf die Ablage benutzen (Karte 1559)? Angemeldet und - mit eingeschaltetem Seitenschutz - berechtigt für die
 * Seite {@value #VIEW}. Wie {@code DrawioSeitenZugriff} im Inventar; REST und Seite fragen dasselbe.
 */
@Component
@RequiredArgsConstructor
public class FilesZugriff {

    public static final String VIEW = "/files/ablage.xhtml";

    private final ObjectProvider<PageAccessGuardService> guard;

    /** @return die Kennung des Benutzers, {@code null} ohne Anmeldung oder Recht */
    public String benutzer() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        PageAccessGuardService g = guard.getIfAvailable();
        return g == null || !g.isEnabled() || g.hasAccessToView(VIEW) ? auth.getName() : null;
    }
}

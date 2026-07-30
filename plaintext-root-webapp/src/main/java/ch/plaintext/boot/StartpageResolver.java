/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot;

import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.regex.Pattern;

/**
 * Löst die individuelle Start-/Landing-Page eines Benutzers aus seinen {@link GrantedAuthority}s auf
 * und sichert sie ab. Eine konfigurierte Startseite (Authority {@code PROPERTY_STARTPAGE_<page>}) wird
 * nur verwendet, wenn sie wie ein gültiger app-interner Seitenpfad aussieht; andernfalls
 * (leer/ungültig) fällt sie auf {@link #DEFAULT_PAGE} zurück, damit kein Benutzer von der Startseite
 * ausgesperrt wird. So bleiben individuelle Startseiten erhalten, ein kaputter Wert führt aber immer
 * verlässlich auf index.html.
 *
 * @author plaintext.ch
 */
public final class StartpageResolver {

    /** Standard-Landing-Page, wenn keine gültige individuelle Startseite gesetzt ist. */
    public static final String DEFAULT_PAGE = "index.html";

    private static final String STARTPAGE_PREFIX = "PROPERTY_STARTPAGE_";

    /**
     * Erlaubt einen relativen Seitenpfad aus Pfadsegmenten (Buchstaben/Ziffern/{@code _-}), der auf
     * {@code .html} oder {@code .xhtml} endet, optional gefolgt von einem einfachen Query-String –
     * kein Schema, kein führender Slash (auch kein protokoll-relatives {@code //host}), kein {@code ..}.
     */
    private static final Pattern SAFE_PAGE =
            Pattern.compile("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*\\.x?html(?:\\?[A-Za-z0-9_=&%.+-]*)?");

    private StartpageResolver() {
    }

    /**
     * Liefert die abgesicherte Startseite (relativer Pfad ohne führenden Slash) für die gegebenen
     * Authorities: die konfigurierte {@code PROPERTY_STARTPAGE_}-Seite, falls gültig, sonst
     * {@link #DEFAULT_PAGE}.
     *
     * @param authorities die GrantedAuthorities des Benutzers (darf {@code null} sein)
     * @return ein gültiger relativer Seitenpfad
     */
    public static String resolve(Collection<? extends GrantedAuthority> authorities) {
        String page = DEFAULT_PAGE;
        if (authorities != null) {
            for (GrantedAuthority authority : authorities) {
                String authStr = authority.getAuthority();
                if (authStr != null && authStr.startsWith(STARTPAGE_PREFIX)) {
                    page = authStr.substring(STARTPAGE_PREFIX.length());
                    break; // die erste konfigurierte Startseite gewinnt
                }
            }
        }
        return safe(page);
    }

    /**
     * Validiert einen Startseiten-Wert; liefert ihn nur zurück, wenn er wie ein gültiger app-interner
     * Seitenpfad aussieht, sonst {@link #DEFAULT_PAGE}.
     *
     * @param page der zu prüfende Seitenpfad (darf {@code null} sein)
     * @return der getrimmte Pfad, wenn gültig, sonst {@link #DEFAULT_PAGE}
     */
    public static String safe(String page) {
        if (page == null) {
            return DEFAULT_PAGE;
        }
        String trimmed = page.trim();
        return SAFE_PAGE.matcher(trimmed).matches() ? trimmed : DEFAULT_PAGE;
    }
}

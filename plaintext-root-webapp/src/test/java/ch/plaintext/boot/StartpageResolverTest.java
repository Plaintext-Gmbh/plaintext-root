/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests für {@link StartpageResolver}: gültige individuelle Startseiten bleiben erhalten, leere oder
 * ungültige Werte fallen verlässlich auf {@code index.html} zurück.
 */
class StartpageResolverTest {

    @Test
    void resolve_nullAuthorities_returnsIndex() {
        assertEquals("index.html", StartpageResolver.resolve(null));
    }

    @Test
    void resolve_noStartpageAuthority_returnsIndex() {
        assertEquals("index.html", StartpageResolver.resolve(
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test
    void resolve_validStartpage_returnsThatPage() {
        assertEquals("zeiterfassung.html", StartpageResolver.resolve(Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_STARTPAGE_zeiterfassung.html"))));
    }

    @Test
    void resolve_validStartpageWithHyphenAndQuery_returnsThatPage() {
        assertEquals("wander-tour.html?id=2", StartpageResolver.resolve(Collections.singletonList(
                new SimpleGrantedAuthority("PROPERTY_STARTPAGE_wander-tour.html?id=2"))));
    }

    @Test
    void resolve_firstStartpageWins() {
        // Bei mehreren konfigurierten Startseiten gewinnt die erste (wie der Login-Redirect).
        assertEquals("first.html", StartpageResolver.resolve(Arrays.asList(
                new SimpleGrantedAuthority("PROPERTY_STARTPAGE_first.html"),
                new SimpleGrantedAuthority("PROPERTY_STARTPAGE_second.html"))));
    }

    @Test
    void resolve_emptyStartpage_fallsBackToIndex() {
        assertEquals("index.html", StartpageResolver.resolve(Collections.singletonList(
                new SimpleGrantedAuthority("PROPERTY_STARTPAGE_"))));
    }

    @Test
    void safe_rejectsDangerousAndInvalidValues() {
        List<String> bad = Arrays.asList(
                null,
                "",
                "   ",
                "javascript:alert(1)",
                "//evil.example.com",
                "https://evil.example.com",
                "/etc/passwd",
                "../secret.html",
                "foo.txt",
                "noextension");
        for (String value : bad) {
            assertEquals("index.html", StartpageResolver.safe(value), "sollte Fallback sein: " + value);
        }
    }

    @Test
    void safe_acceptsPlainAndNestedPages() {
        assertEquals("index.html", StartpageResolver.safe("index.html"));
        assertEquals("dashboard.xhtml", StartpageResolver.safe("dashboard.xhtml"));
        assertEquals("admin/uebersicht.html", StartpageResolver.safe("admin/uebersicht.html"));
    }
}

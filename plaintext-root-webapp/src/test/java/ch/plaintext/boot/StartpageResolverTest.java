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
 * Tests for {@link StartpageResolver}: valid individual start pages are preserved, empty or
 * invalid values fall back reliably to {@code index.html}.
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
        // With several configured start pages the first one wins (like the login redirect).
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

    // ── Card 1331: the page has to exist ──────────────────────────────────────────────────────

    /** Pages of a fictitious application: index and auszahlungen exist, nothing else. */
    private static final java.util.function.Predicate<String> SEITEN =
            p -> p.equals("index.html") || p.startsWith("auszahlungen.html");

    @Test
    void resolve_nonExistingStartpage_fallsBackToIndex() {
        // The start page of 23.09.2026: capital I. Form-valid, but the page is index.xhtml.
        for (String seite : List.of("Index.html", "gibtesnicht.html", "admin/weg.html")) {
            assertEquals("index.html", StartpageResolver.resolve(Collections.singletonList(
                    new SimpleGrantedAuthority("PROPERTY_STARTPAGE_" + seite)), SEITEN), seite);
        }
    }

    @Test
    void resolve_existingStartpage_isKept_positiveControl() {
        assertEquals("auszahlungen.html", StartpageResolver.resolve(Collections.singletonList(
                new SimpleGrantedAuthority("PROPERTY_STARTPAGE_auszahlungen.html")), SEITEN));
        assertEquals("auszahlungen.html?jahr=2026", StartpageResolver.resolve(Collections.singletonList(
                new SimpleGrantedAuthority("PROPERTY_STARTPAGE_auszahlungen.html?jahr=2026")), SEITEN));
    }

    @Test
    void existsIn_looksUpTheFaceletsViewWithoutQuery() throws Exception {
        jakarta.servlet.ServletContext ctx = org.mockito.Mockito.mock(jakarta.servlet.ServletContext.class);
        org.mockito.Mockito.when(ctx.getResource("/auszahlungen.xhtml")).thenReturn(new java.net.URL("file:/x"));
        org.mockito.Mockito.when(ctx.getResource("/admin/uebersicht.xhtml")).thenReturn(new java.net.URL("file:/y"));
        java.util.function.Predicate<String> existiert = StartpageResolver.existsIn(ctx);
        org.junit.jupiter.api.Assertions.assertTrue(existiert.test("auszahlungen.html"));
        org.junit.jupiter.api.Assertions.assertTrue(existiert.test("auszahlungen.html?jahr=2026"));
        org.junit.jupiter.api.Assertions.assertTrue(existiert.test("auszahlungen.xhtml"));
        org.junit.jupiter.api.Assertions.assertTrue(existiert.test("admin/uebersicht.html"));
        org.junit.jupiter.api.Assertions.assertFalse(existiert.test("Index.html"));
        org.junit.jupiter.api.Assertions.assertTrue(StartpageResolver.existsIn(null).test("Index.html"),
                "Ohne ServletContext bleibt es bei der Formpruefung");
    }

    @Test
    void rejectionReason_forSaving() {
        org.junit.jupiter.api.Assertions.assertNull(StartpageResolver.rejectionReason(null, SEITEN));
        org.junit.jupiter.api.Assertions.assertNull(StartpageResolver.rejectionReason("  ", SEITEN));
        org.junit.jupiter.api.Assertions.assertNull(StartpageResolver.rejectionReason("auszahlungen.html", SEITEN));
        org.junit.jupiter.api.Assertions.assertNull(StartpageResolver.rejectionReason(" index.html ", SEITEN));
        String grund = StartpageResolver.rejectionReason("Index.html", SEITEN);
        org.junit.jupiter.api.Assertions.assertNotNull(grund);
        org.junit.jupiter.api.Assertions.assertTrue(grund.contains("gibt es in dieser Anwendung nicht"), grund);
        org.junit.jupiter.api.Assertions.assertTrue(
                StartpageResolver.rejectionReason("javascript:alert(1)", SEITEN).contains("kein gueltiger Seitenpfad"));
    }
}

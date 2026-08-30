/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DashboardTileData}: CSS-safe status color
 * ({@link DashboardTileData#getSafeStatusColor()}) and safe tile links
 * ({@link DashboardTileData.TileAction#getSafeLink()}).
 *
 * @author plaintext.ch
 */
class DashboardTileDataTest {

    private DashboardTileData withColor(String color) {
        DashboardTileData tile = new DashboardTileData();
        tile.setStatusColor(color);
        return tile;
    }

    private DashboardTileData.TileAction withLink(String link) {
        return new DashboardTileData.TileAction("Label", link);
    }

    @Test
    void shouldAcceptSixDigitHex() {
        assertEquals("#3cb44b", withColor("#3cb44b").getSafeStatusColor());
        assertEquals("#888888", withColor("#888888").getSafeStatusColor());
    }

    @Test
    void shouldAcceptThreeAndEightDigitHex() {
        assertEquals("#abc", withColor("#abc").getSafeStatusColor());
        assertEquals("#11223344", withColor("#11223344").getSafeStatusColor());
    }

    @Test
    void shouldAcceptNamedColor() {
        assertEquals("red", withColor("red").getSafeStatusColor());
        assertEquals("rebeccapurple", withColor("rebeccapurple").getSafeStatusColor());
    }

    @Test
    void shouldTrimSurroundingWhitespace() {
        assertEquals("#3cb44b", withColor("  #3cb44b  ").getSafeStatusColor());
    }

    @Test
    void shouldReturnNullWhenStatusColorNull() {
        assertNull(new DashboardTileData().getSafeStatusColor());
    }

    @Test
    void shouldRejectCssInjectionAttempts() {
        // Attempt to break out of the CSS context or to exfiltrate data
        assertNull(withColor("red; background:url(https://evil.example/x)").getSafeStatusColor());
        assertNull(withColor("url(https://evil.example/x)").getSafeStatusColor());
        assertNull(withColor("#fff;}body{display:none").getSafeStatusColor());
        assertNull(withColor("rgb(1,2,3)").getSafeStatusColor());
        assertNull(withColor("expression(alert(1))").getSafeStatusColor());
    }

    @Test
    void shouldRejectInvalidHexLength() {
        assertNull(withColor("#12").getSafeStatusColor());
        assertNull(withColor("#12345").getSafeStatusColor());
        assertNull(withColor("#zzzzzz").getSafeStatusColor());
    }

    // --- getSafeLink ---

    @Test
    void safeLinkShouldAcceptRelativePaths() {
        assertEquals("bieler-map.html", withLink("bieler-map.html").getSafeLink());
        assertEquals("/x/y/z", withLink("/x/y/z").getSafeLink());
        assertEquals("./y?a=1#fragment", withLink("./y?a=1#fragment").getSafeLink());
        assertEquals("../up", withLink("../up").getSafeLink());
    }

    @Test
    void safeLinkShouldAcceptAllowedSchemes() {
        assertEquals("http://example.com", withLink("http://example.com").getSafeLink());
        assertEquals("https://example.com/path?q=1", withLink("https://example.com/path?q=1").getSafeLink());
        assertEquals("mailto:user@example.com", withLink("mailto:user@example.com").getSafeLink());
        assertEquals("tel:+41791234567", withLink("tel:+41791234567").getSafeLink());
    }

    @Test
    void safeLinkShouldRejectJavascriptScheme() {
        assertNull(withLink("javascript:alert(1)").getSafeLink());
    }

    @Test
    void safeLinkShouldRejectJavascriptCaseInsensitive() {
        assertNull(withLink("JavaScript:alert(1)").getSafeLink());
        assertNull(withLink("JAVASCRIPT:void(0)").getSafeLink());
    }

    @Test
    void safeLinkShouldRejectJavascriptWithLeadingWhitespace() {
        assertNull(withLink("  javascript:alert(1)").getSafeLink());
        assertNull(withLink("\tjavascript:alert(1)").getSafeLink());
    }

    @Test
    void safeLinkShouldRejectJavascriptWithEmbeddedControlChars() {
        // Browsers ignore e.g. \t or \n in the scheme part
        assertNull(withLink("java\tscript:alert(1)").getSafeLink());
        assertNull(withLink("java\nscript:alert(1)").getSafeLink());
    }

    @Test
    void safeLinkShouldRejectProtocolRelativeUrls() {
        // The browser reads protocol-relative URLs as an absolute URL (open redirect)
        assertNull(withLink("//evil.com/").getSafeLink());
        assertNull(withLink("//evil.com/path?q=1").getSafeLink());
        assertNull(withLink("  //evil.com/").getSafeLink());
        assertNull(withLink("/\t/evil.com/").getSafeLink());
    }

    @Test
    void safeLinkShouldRejectDataScheme() {
        assertNull(withLink("data:text/html,<script>alert(1)</script>").getSafeLink());
    }

    @Test
    void safeLinkShouldRejectVbscriptScheme() {
        assertNull(withLink("vbscript:msgbox(1)").getSafeLink());
    }

    @Test
    void safeLinkShouldReturnNullForNullOrEmpty() {
        assertNull(new DashboardTileData.TileAction("Label", null).getSafeLink());
        assertNull(new DashboardTileData.TileAction("Label", "").getSafeLink());
        assertNull(new DashboardTileData.TileAction("Label", "   ").getSafeLink());
    }
}

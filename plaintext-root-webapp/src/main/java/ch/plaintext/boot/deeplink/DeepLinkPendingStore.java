/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.deeplink;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.io.Serializable;
import java.util.Locale;

/**
 * Remembers a deep link across the login flow (card 345, "login case").
 *
 * <h2>Why no target URL is stored</h2>
 * The obvious way — dragging the desired URL through the login as a parameter — is exactly
 * the pattern out of which open redirects arise. Therefore <b>no URL</b> is stored here,
 * only the three validated identifiers {@code type}/{@code mandat}/{@code id} in the
 * <b>server session</b>. After the login a {@code /deeplink} call is built from them again,
 * which runs through the full chain of checks anew. An attacker can thereby neither steer to a
 * foreign target nor skip a check — at worst they send their victim to a
 * page of our own application that the victim has access to anyway.
 */
public final class DeepLinkPendingStore {

    static final String SESSION_ATTRIBUTE = "PLAINTEXT_PENDING_DEEPLINK";

    /** The three identifiers of a deep link — deliberately no free text and no URL. */
    public record PendingDeepLink(String type, String mandat, String id) implements Serializable {
    }

    private DeepLinkPendingStore() {
    }

    /**
     * Stores the deep link for the time after the login. Invalid parameters are not
     * remembered at all (the user then lands on their start page after the login).
     */
    public static void merke(HttpServletRequest request, String type, String mandat, String id) {
        // Normalize first, then check — exactly as in the DeepLinkResolver. Otherwise a
        // completely harmless link with a capitalized tenant would fail the pattern check.
        String t = type == null ? null : type.trim().toLowerCase(Locale.ROOT);
        String m = mandat == null ? null : mandat.trim().toLowerCase(Locale.ROOT);
        String i = id == null ? null : id.trim();
        if (!DeepLinkFormat.istGueltigerType(t)
                || !DeepLinkFormat.istGueltigesMandat(m)
                || !DeepLinkFormat.istGueltigeId(i)) {
            return;
        }
        request.getSession(true).setAttribute(SESSION_ATTRIBUTE, new PendingDeepLink(t, m, i));
    }

    /**
     * Reads the remembered deep link and removes it immediately (single use), so that a
     * one-off mail click does not redirect every subsequent login.
     */
    public static PendingDeepLink entnehme(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object wert = session.getAttribute(SESSION_ATTRIBUTE);
        session.removeAttribute(SESSION_ATTRIBUTE);
        return (wert instanceof PendingDeepLink pending) ? pending : null;
    }

    /**
     * Builds the (context-relative) {@code /deeplink} call from a remembered deep link. All
     * parts come from the validated fields of the record, not from the request.
     */
    public static String alsPfad(PendingDeepLink pending) {
        // The validation applies here a second time: the record comes from the session and must
        // not be a smuggling route either, even if somebody else filled it.
        if (pending == null
                || !DeepLinkFormat.istGueltigerType(pending.type())
                || !DeepLinkFormat.istGueltigesMandat(pending.mandat())
                || !DeepLinkFormat.istGueltigeId(pending.id())) {
            return null;
        }
        return DeepLinkService.DEEPLINK_PATH
                + "?type=" + pending.type()
                + "&mandat=" + pending.mandat()
                + "&id=" + pending.id();
    }
}

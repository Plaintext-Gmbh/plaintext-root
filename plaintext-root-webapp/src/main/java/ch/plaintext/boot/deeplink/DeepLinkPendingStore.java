/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.deeplink;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.io.Serializable;
import java.util.Locale;

/**
 * Merkt einen Deep-Link ueber den Login-Flow hinweg (Karte 345, „Login-Fall").
 *
 * <h2>Warum keine Ziel-URL gespeichert wird</h2>
 * Der naheliegende Weg — die gewuenschte URL als Parameter durch den Login schleifen — ist genau
 * das Muster, aus dem Open Redirects entstehen. Hier wird deshalb <b>keine URL</b> abgelegt,
 * sondern nur die drei validierten Bezeichner {@code type}/{@code mandat}/{@code id} in der
 * <b>Server-Session</b>. Nach dem Login wird daraus wieder ein {@code /deeplink}-Aufruf gebaut,
 * der erneut die volle Pruefkette durchlaeuft. Ein Angreifer kann damit weder ein fremdes Ziel
 * ansteuern noch eine Pruefung ueberspringen — schlimmstenfalls schickt er sein Opfer auf eine
 * Seite der eigenen Anwendung, auf die es ohnehin Zugriff hat.
 */
public final class DeepLinkPendingStore {

    static final String SESSION_ATTRIBUTE = "PLAINTEXT_PENDING_DEEPLINK";

    /** Die drei Bezeichner eines Deep-Links — bewusst kein freier Text und keine URL. */
    public record PendingDeepLink(String type, String mandat, String id) implements Serializable {
    }

    private DeepLinkPendingStore() {
    }

    /**
     * Legt den Deep-Link fuer die Zeit nach dem Login ab. Ungueltige Parameter werden gar nicht
     * erst gemerkt (dann landet der Benutzer nach dem Login auf seiner Startseite).
     */
    public static void merke(HttpServletRequest request, String type, String mandat, String id) {
        // Erst normalisieren, dann pruefen — genau wie im DeepLinkResolver. Sonst scheitert ein
        // voellig harmloser Link mit grossgeschriebenem Mandanten an der Mustertruefung.
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
     * Liest den gemerkten Deep-Link und entfernt ihn sofort (Einmal-Verwendung), damit ein
     * einmaliger Mail-Klick nicht jeden weiteren Login umleitet.
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
     * Baut aus einem gemerkten Deep-Link den (context-relativen) {@code /deeplink}-Aufruf. Alle
     * Bestandteile stammen aus den validierten Feldern des Records, nicht aus dem Request.
     */
    public static String alsPfad(PendingDeepLink pending) {
        // Die Validierung greift hier ein zweites Mal: der Record kommt aus der Session und soll
        // auch dann kein Schmuggelweg sein, wenn ihn jemand anders befuellt hat.
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

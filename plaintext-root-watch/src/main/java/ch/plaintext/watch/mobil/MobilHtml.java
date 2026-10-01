/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.mobil;

import org.springframework.web.util.HtmlUtils;

import java.util.List;

/**
 * Turns a {@link MobilSeite} into HTML (Karte 1355).
 *
 * <h2>Why a hundred lines of Java and not a template engine</h2>
 *
 * <p>The vocabulary is closed — four blocks and a frame. A template engine would bring a
 * second syntax, a resolver, a cache and a jar of a megabyte into every application that
 * carries {@code plaintext-root-watch}, for markup that fits on one screen. What a template
 * engine gives for free, escaping, happens here in exactly one method ({@link #e(String)}),
 * and {@code MobilHtmlTest} holds it against {@code <script>} in every field.</p>
 *
 * <h2>The three rules this class keeps for every page</h2>
 *
 * <ol>
 *   <li><b>No inline script, no {@code on…} attribute, no {@code style} attribute.</b> The CSP
 *       of this house runs without {@code 'unsafe-inline'} as soon as an application switches
 *       it off; everything interactive hangs off {@code data-} attributes that
 *       {@code mobil.js} reads.</li>
 *   <li><b>Every action is a real form.</b> {@code POST} with the CSRF field of the session,
 *       so the page works with JavaScript switched off or not yet loaded — the script only
 *       takes the page reload out of it.</li>
 *   <li><b>Only classes from {@code watch.css}.</b> The Facelet pages and these pages look the
 *       same because they are drawn with the same stylesheet; {@code MobilHtmlTest} checks
 *       every class against it, like {@code WatchStilVertragTest} does for the Facelets.</li>
 * </ol>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public final class MobilHtml {

    /** Everything the frame needs besides the content. Addresses include the context path. */
    public record Rahmen(String titel, String position, String weiterAdresse,
                         String uebersichtAdresse, String cssAdresse, String jsAdresse,
                         String meldung, boolean meldungFehler) {
    }

    /**
     * Everything the forms need.
     *
     * @param seitenAdresse address of the page itself (GET), with context path
     * @param aktionsBasis  prefix of every action address, ending in {@code /}
     * @param csrfName      name of the CSRF field, {@code null} when the application has none
     * @param csrfWert      the token
     * @param frage         key of the entry whose deletion is to be shown as asked
     *                      (the no-JavaScript path), {@code null} for none
     */
    public record Formular(String seitenAdresse, String aktionsBasis, String csrfName,
                           String csrfWert, String frage) {
    }

    /** The id of the element the content is swapped into. {@code mobil.js} knows it by this. */
    public static final String INHALT_ID = "m-inhalt";

    /** The id of the status line. */
    public static final String MELDUNG_ID = "m-meldung";

    private MobilHtml() {
    }

    /** A whole page. */
    public static String seite(Rahmen r, String inhalt) {
        StringBuilder b = new StringBuilder(4096);
        b.append("<!DOCTYPE html>\n<html lang=\"de\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, viewport-fit=cover\">")
                .append("<meta name=\"robots\" content=\"noindex,nofollow\">")
                // Wie frame.xhtml (Karte 1257): die Uhr wird ueber einen Link mit Token
                // geoeffnet, keine Verweisadresse darf ihn weitertragen.
                .append("<meta name=\"referrer\" content=\"no-referrer\">")
                .append("<meta name=\"apple-mobile-web-app-capable\" content=\"yes\">")
                .append("<meta name=\"apple-mobile-web-app-status-bar-style\" content=\"black-translucent\">")
                .append("<meta name=\"mobile-web-app-capable\" content=\"yes\">")
                .append("<meta name=\"theme-color\" content=\"#0b0b0f\">")
                .append("<link rel=\"stylesheet\" href=\"").append(e(r.cssAdresse())).append("\">")
                .append("<script src=\"").append(e(r.jsAdresse())).append("\" defer></script>")
                .append("<title>").append(e(r.titel())).append("</title></head>")
                .append("<body><div class=\"w-wrap\">")
                .append("<div class=\"w-head\"><h1>").append(e(r.titel())).append("</h1>")
                .append("<span class=\"w-pos\">").append(e(r.position())).append("</span></div>");

        boolean leer = r.meldung() == null || r.meldung().isBlank();
        b.append("<div id=\"").append(MELDUNG_ID).append("\" class=\"w-hint")
                .append(r.meldungFehler() ? " w-card-warn" : "")
                .append("\" role=\"status\" aria-live=\"polite\"").append(leer ? " hidden" : "").append('>')
                .append(leer ? "" : e(r.meldung())).append("</div>");

        b.append("<main id=\"").append(INHALT_ID).append("\">").append(inhalt).append("</main>");

        if (r.weiterAdresse() != null && !r.weiterAdresse().isBlank()) {
            boolean uebersicht = r.uebersichtAdresse() != null && !r.uebersichtAdresse().isBlank();
            // Ein Link und kein Formular: weiter blaettern aendert nichts, es zeigt nur die
            // naechste Seite — und die merkt sich die Position beim Anzeigen selbst.
            b.append("<div class=\"w-nav\"><a class=\"w-aktion\" aria-label=\"weiter\" href=\"")
                    .append(e(r.weiterAdresse())).append('"');
            if (uebersicht) {
                b.append(" data-lang-ziel=\"").append(e(r.uebersichtAdresse())).append('"');
            }
            b.append("><span class=\"w-aktion-wort\">›</span>");
            if (uebersicht) {
                b.append("<span class=\"w-aktion-hinweis\">lang drücken: Übersicht</span>");
            }
            b.append("</a></div>");
        }
        b.append("</div></body></html>");
        return b.toString();
    }

    /** The content of a page, the part that is swapped after an action. */
    public static String inhalt(MobilSeite seite, Formular f) {
        StringBuilder b = new StringBuilder(2048);
        int zaehler = 0;
        for (MobilSeite.Baustein baustein : seite.bausteine()) {
            switch (baustein) {
                case MobilSeite.Wert w -> wert(b, w);
                case MobilSeite.Knoepfe k -> knoepfe(b, k, f);
                case MobilSeite.Liste l -> zaehler = liste(b, l, f, zaehler);
                case MobilSeite.Hinweis h -> b.append("<div class=\"w-card\"><div class=\"w-note\">")
                        .append(e(h.text())).append("</div></div>");
            }
        }
        return b.toString();
    }

    private static void wert(StringBuilder b, MobilSeite.Wert w) {
        b.append("<div class=\"w-card\"><div class=\"w-widget\"><div class=\"w-value\">").append(e(w.wert()))
                .append("</div><div class=\"w-label\">").append(e(w.label())).append("</div></div>");
        if (w.hinweis() != null && !w.hinweis().isBlank()) {
            b.append("<div class=\"w-hint\">").append(e(w.hinweis())).append("</div>");
        }
        b.append("</div>");
    }

    private static void knoepfe(StringBuilder b, MobilSeite.Knoepfe k, Formular f) {
        List<MobilSeite.Knopf> liste = k.knoepfe();
        if (liste.isEmpty()) {
            return;
        }
        b.append("<div class=\"w-card\">");
        if (k.label() != null && !k.label().isBlank()) {
            b.append("<div class=\"w-label\">").append(e(k.label())).append("</div>");
        }
        b.append("<form class=\"w-chooser\" method=\"post\" data-mobil action=\"")
                .append(e(f.aktionsBasis() + liste.get(0).aktion())).append("\">");
        csrf(b, f);
        for (MobilSeite.Knopf knopf : liste) {
            b.append("<button type=\"submit\" class=\"w-chip\" formaction=\"")
                    .append(e(f.aktionsBasis() + knopf.aktion())).append('"');
            if (knopf.wert() != null) {
                b.append(" name=\"wert\" value=\"").append(e(knopf.wert())).append('"');
            }
            b.append('>').append(e(knopf.beschriftung())).append("</button>");
        }
        b.append("</form></div>");
    }

    private static int liste(StringBuilder b, MobilSeite.Liste l, Formular f, int zaehler) {
        b.append("<div class=\"w-card\"><div class=\"w-label\">").append(e(l.label())).append("</div>");
        if (l.eintraege().isEmpty()) {
            b.append("<div class=\"w-hint\">").append(e(l.leerText())).append("</div>");
        }
        int n = zaehler;
        for (MobilSeite.Eintrag eintrag : l.eintraege()) {
            String frageId = "m-frage-" + (n++);
            boolean loeschbar = eintrag.loeschAktion() != null && eintrag.schluessel() != null;
            boolean gefragt = loeschbar && eintrag.schluessel().equals(f.frage());
            b.append("<div class=\"w-entry\">");
            // Die Zeile ist ein GET-Formular auf die Seite selbst: ohne JavaScript fuehrt das ×
            // auf dieselbe Seite mit offener Rueckfrage (?frage=…), mit JavaScript klappt
            // mobil.js die Rueckfrage an Ort und Stelle auf, ganz ohne Anfrage.
            b.append("<form class=\"w-row\" method=\"get\" action=\"").append(e(f.seitenAdresse())).append("\">")
                    .append("<span class=\"w-entry-label\">").append(e(eintrag.text())).append("</span>");
            if (eintrag.rechts() != null) {
                b.append("<span class=\"w-entry-total\">").append(e(eintrag.rechts())).append("</span>");
            }
            if (loeschbar) {
                b.append("<button type=\"submit\" class=\"w-btn w-btn-small\" name=\"frage\" value=\"")
                        .append(e(eintrag.schluessel())).append("\" data-frage=\"").append(frageId)
                        .append("\" aria-label=\"Löschen\" aria-controls=\"").append(frageId).append("\">×</button>");
            }
            b.append("</form>");
            if (loeschbar) {
                b.append("<div class=\"w-confirm\" id=\"").append(frageId).append('"')
                        .append(gefragt ? "" : " hidden").append("><div class=\"w-confirm-text\">Löschen?</div>")
                        .append("<form class=\"w-row\" method=\"post\" data-mobil action=\"")
                        .append(e(f.aktionsBasis() + eintrag.loeschAktion())).append("\">");
                csrf(b, f);
                b.append("<button type=\"submit\" class=\"w-btn w-btn-stop\" name=\"wert\" value=\"")
                        .append(e(eintrag.schluessel())).append("\">Ja</button>")
                        .append("<a class=\"w-btn\" href=\"").append(e(f.seitenAdresse()))
                        .append("\" data-zu=\"").append(frageId).append("\">Nein</a>")
                        .append("</form></div>");
            }
            b.append("</div>");
        }
        b.append("</div>");
        return n;
    }

    private static void csrf(StringBuilder b, Formular f) {
        if (f.csrfName() != null && f.csrfWert() != null) {
            b.append("<input type=\"hidden\" name=\"").append(e(f.csrfName()))
                    .append("\" value=\"").append(e(f.csrfWert())).append("\">");
        }
    }

    /** The one place where text becomes HTML. */
    static String e(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s, "UTF-8");
    }
}

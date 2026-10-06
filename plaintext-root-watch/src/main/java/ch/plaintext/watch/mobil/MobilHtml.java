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
 * <p>The vocabulary is closed — a handful of blocks and a frame. A template engine would bring a
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

    /** Formular schliessen (Karte 1416, Sonar java:S1192). */
    private static final String FORMULAR_ZU = "</form>";

    /** Formular einer Zeile oeffnen, Aktion folgt (Karte 1416, Sonar java:S1192). */
    private static final String FORMULAR_AUF = "<form class=\"w-row\" method=\"post\" data-mobil action=\"";

    /** Span schliessen (Karte 1416, Sonar java:S1192). */
    private static final String SPAN_ZU = "</span>";

    /** Hinweis oeffnen (Karte 1416, Sonar java:S1192). */
    private static final String HINWEIS_AUF = "<div class=\"w-hint\">";

    /** Container schliessen (Karte 1416, Sonar java:S1192). */
    private static final String DIV_ZU = "</div>";

    /** zwei Container schliessen (Karte 1416, Sonar java:S1192). */
    private static final String DIV_ZU_ZU = "</div></div>";

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

    /**
     * Prefix of the request parameter of a field ({@link MobilSeite.Feld}). The controller hands
     * only parameters with this prefix to the page — never the CSRF token or anything else that
     * happens to be in the request.
     */
    public static final String FELD_PRAEFIX = "f-";

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
                .append(leer ? "" : e(r.meldung())).append(DIV_ZU);

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
                case MobilSeite.Hinweis(String text) -> b.append("<div class=\"w-card\"><div class=\"w-note\">")
                        .append(e(text)).append(DIV_ZU_ZU);
                case MobilSeite.Kacheln k -> kacheln(b, k);
                case MobilSeite.Aktion a -> aktion(b, a, f);
                case MobilSeite.Schalter s -> schalter(b, s, f);
            }
        }
        return b.toString();
    }

    private static void wert(StringBuilder b, MobilSeite.Wert w) {
        b.append("<div class=\"w-card\"><div class=\"w-widget\"><div class=\"w-value\">").append(e(w.wert()))
                .append("</div><div class=\"w-label\">").append(e(w.label())).append(DIV_ZU_ZU);
        if (w.hinweis() != null && !w.hinweis().isBlank()) {
            b.append(HINWEIS_AUF).append(e(w.hinweis())).append(DIV_ZU);
        }
        b.append(DIV_ZU);
    }

    private static void knoepfe(StringBuilder b, MobilSeite.Knoepfe k, Formular f) {
        List<MobilSeite.Knopf> liste = k.knoepfe();
        if (liste.isEmpty()) {
            return;
        }
        b.append("<div class=\"w-card\">");
        if (k.label() != null && !k.label().isBlank()) {
            b.append("<div class=\"w-label\">").append(e(k.label())).append(DIV_ZU);
        }
        b.append("<form class=\"w-chooser\" method=\"post\" data-mobil action=\"")
                .append(e(f.aktionsBasis() + liste.get(0).aktion())).append("\">");
        csrf(b, f);
        for (MobilSeite.Knopf knopf : liste) {
            b.append("<button type=\"submit\" class=\"w-chip").append(knopf.gewaehlt() ? " w-chip-an" : "")
                    .append("\" formaction=\"").append(e(f.aktionsBasis() + knopf.aktion())).append('"');
            if (knopf.gewaehlt()) {
                b.append(" aria-pressed=\"true\"");
            }
            wertAttribut(b, knopf.wert());
            b.append('>').append(e(knopf.beschriftung())).append("</button>");
        }
        b.append("</form></div>");
    }

    private static void wertAttribut(StringBuilder b, String wert) {
        if (wert != null) {
            b.append(" name=\"wert\" value=\"").append(e(wert)).append('"');
        }
    }

    private static void kacheln(StringBuilder b, MobilSeite.Kacheln k) {
        b.append("<div class=\"w-card\">");
        if (k.kacheln().isEmpty()) {
            b.append("<div class=\"w-note\">").append(e(k.leerText())).append(DIV_ZU_ZU);
            return;
        }
        // Wie home.xhtml bis Karte 1387: ab drei Kacheln drei nebeneinander, sonst zwei.
        b.append("<div class=\"w-widgets").append(k.kacheln().size() >= 3 ? " w-3" : "").append("\">");
        for (MobilSeite.Kachel kachel : k.kacheln()) {
            b.append("<div class=\"w-widget\"><div class=\"w-value\">").append(e(kachel.wert()))
                    .append("</div><div class=\"w-label\">").append(e(kachel.label())).append(DIV_ZU_ZU);
        }
        b.append(DIV_ZU_ZU);
    }

    /**
     * The large acting button. A real {@code <button>} this time: unlike the
     * {@code <input type="submit">} of {@code h:commandButton} it can carry three lines, so the
     * transparent overlay of {@code w:aktion} is not needed — what one sees is the button.
     */
    private static void aktion(StringBuilder b, MobilSeite.Aktion a, Formular f) {
        String farbe = switch (a.farbe()) {
            case GO -> " w-aktion-go";
            case STOP -> " w-aktion-stop";
            case NEUTRAL -> "";
        };
        b.append("<div class=\"w-card\"><form method=\"post\" data-mobil action=\"")
                .append(e(f.aktionsBasis() + a.knopf().aktion())).append("\">");
        csrf(b, f);
        b.append("<button type=\"submit\" class=\"w-aktion").append(farbe).append('"');
        wertAttribut(b, a.knopf().wert());
        b.append('>');
        if (a.oben() != null && !a.oben().isBlank()) {
            b.append("<span class=\"w-aktion-oben\">").append(e(a.oben())).append(SPAN_ZU);
        }
        if (a.laeuftSekunden() != null) {
            // Die laufende Zeit zaehlt im Browser weiter (mobil.js). Uebergeben wird die Dauer und
            // nicht der Startzeitpunkt: die Uhr des Telefons darf falsch gehen, ohne dass die
            // Anzeige es tut.
            b.append("<span class=\"w-aktion-gross\" data-laeuft=\"").append(Math.max(0, a.laeuftSekunden()))
                    .append("\">").append(e(MobilSeite.dauer(a.laeuftSekunden()))).append(SPAN_ZU);
        } else if (a.gross() != null && !a.gross().isBlank()) {
            b.append("<span class=\"w-aktion-gross\">").append(e(a.gross())).append(SPAN_ZU);
        }
        b.append("<span class=\"w-aktion-wort\">").append(e(a.knopf().beschriftung())).append(SPAN_ZU)
                .append("</button></form>");
        if (!a.neben().isEmpty()) {
            // Die Nebenhandlung (−1) bleibt ein eigener, kleiner Knopf UNTER dem grossen — die
            // Korrektur eines Fehlgriffs ist nicht die Handlung der Seite (Karte 1312).
            b.append(FORMULAR_AUF)
                    .append(e(f.aktionsBasis() + a.neben().get(0).aktion())).append("\">");
            csrf(b, f);
            for (MobilSeite.Knopf k : a.neben()) {
                b.append("<button type=\"submit\" class=\"w-btn\" formaction=\"")
                        .append(e(f.aktionsBasis() + k.aktion())).append('"');
                wertAttribut(b, k.wert());
                b.append('>').append(e(k.beschriftung())).append("</button>");
            }
            b.append(FORMULAR_ZU);
        }
        b.append(DIV_ZU);
    }

    private static void schalter(StringBuilder b, MobilSeite.Schalter s, Formular f) {
        b.append("<div class=\"w-card\"><div class=\"w-label\">").append(e(s.label())).append(DIV_ZU);
        for (MobilSeite.SchalterZeile z : s.zeilen()) {
            b.append(FORMULAR_AUF)
                    .append(e(f.aktionsBasis() + z.aktion())).append("\">");
            csrf(b, f);
            b.append("<span class=\"w-value w-sm\">").append(e(z.titel())).append(SPAN_ZU)
                    .append("<button type=\"submit\" class=\"w-btn ").append(z.an() ? "w-btn-go" : "w-btn-stop")
                    .append("\" aria-pressed=\"").append(z.an()).append("\" aria-label=\"")
                    .append(e(z.titel())).append(z.an() ? " ist an" : " ist aus").append('"');
            wertAttribut(b, z.wert());
            b.append('>').append(z.an() ? "an" : "aus").append("</button></form>");
        }
        if (s.hinweis() != null && !s.hinweis().isBlank()) {
            b.append("<div class=\"w-note\">").append(e(s.hinweis())).append(DIV_ZU);
        }
        b.append(DIV_ZU);
    }

    private static int liste(StringBuilder b, MobilSeite.Liste l, Formular f, int zaehler) {
        b.append("<div class=\"w-card\"><div class=\"w-label\">").append(e(l.label())).append(DIV_ZU);
        if (l.eintraege().isEmpty()) {
            b.append(HINWEIS_AUF).append(e(l.leerText())).append(DIV_ZU);
        }
        int n = zaehler;
        for (MobilSeite.Eintrag eintrag : l.eintraege()) {
            eintrag(b, eintrag, f, n++);
        }
        b.append(DIV_ZU);
        return n;
    }

    /** Eine Zeile der Liste mit Rueckfrage zum Loeschen (Karte 1416, Sonar java:S3776: aus liste() herausgeloest). */
    private static void eintrag(StringBuilder b, MobilSeite.Eintrag eintrag, Formular f, int nummer) {
        String frageId = "m-frage-" + nummer;
        boolean loeschbar = eintrag.loeschAktion() != null && eintrag.schluessel() != null;
        boolean gefragt = loeschbar && eintrag.schluessel().equals(f.frage());
        b.append("<div class=\"w-entry\">");
        if (eintrag.aenderung() != null && eintrag.schluessel() != null) {
            felder(b, eintrag, f);
        }
        // Die Zeile ist ein GET-Formular auf die Seite selbst: ohne JavaScript fuehrt das ×
        // auf dieselbe Seite mit offener Rueckfrage (?frage=…), mit JavaScript klappt
        // mobil.js die Rueckfrage an Ort und Stelle auf, ganz ohne Anfrage.
        b.append("<form class=\"w-row\" method=\"get\" action=\"").append(e(f.seitenAdresse())).append("\">");
        if (eintrag.vorne() != null) {
            b.append("<span class=\"w-entry-total\">").append(e(eintrag.vorne())).append(SPAN_ZU);
        }
        b.append("<span class=\"w-entry-label\">").append(e(eintrag.text())).append(SPAN_ZU);
        if (eintrag.rechts() != null) {
            b.append("<span class=\"w-entry-total\">").append(e(eintrag.rechts())).append(SPAN_ZU);
        }
        if (loeschbar) {
            b.append("<button type=\"submit\" class=\"w-btn w-btn-small\" name=\"frage\" value=\"")
                    .append(e(eintrag.schluessel())).append("\" data-frage=\"").append(frageId)
                    .append("\" aria-label=\"Löschen\" aria-controls=\"").append(frageId).append("\">×</button>");
        }
        b.append(FORMULAR_ZU);
        if (eintrag.unter() != null && !eintrag.unter().isBlank()) {
            b.append(HINWEIS_AUF).append(e(eintrag.unter())).append(DIV_ZU);
        }
        if (loeschbar) {
            b.append("<div class=\"w-confirm\" id=\"").append(frageId).append('"')
                    .append(gefragt ? "" : " hidden").append("><div class=\"w-confirm-text\">Löschen?</div>")
                    .append(FORMULAR_AUF)
                    .append(e(f.aktionsBasis() + eintrag.loeschAktion())).append("\">");
            csrf(b, f);
            b.append("<button type=\"submit\" class=\"w-btn w-btn-stop\" name=\"wert\" value=\"")
                    .append(e(eintrag.schluessel())).append("\">Ja</button>")
                    .append("<a class=\"w-btn\" href=\"").append(e(f.seitenAdresse()))
                    .append("\" data-zu=\"").append(frageId).append("\">Nein</a>")
                    .append("</form></div>");
        }
        b.append(DIV_ZU);
    }

    /**
     * The editable fields of an entry: one POST form that {@code mobil.js} sends on every change
     * ({@code data-mobil-auto}). Without JavaScript the OK button inside {@code noscript} sends it.
     */
    private static void felder(StringBuilder b, MobilSeite.Eintrag eintrag, Formular f) {
        MobilSeite.Aenderung a = eintrag.aenderung();
        b.append("<form class=\"w-row\" method=\"post\" data-mobil data-mobil-auto action=\"")
                .append(e(f.aktionsBasis() + a.aktion())).append("\">");
        csrf(b, f);
        b.append("<input type=\"hidden\" name=\"wert\" value=\"").append(e(eintrag.schluessel())).append("\">");
        MobilSeite.Feld vorher = null;
        for (MobilSeite.Feld feld : a.felder()) {
            boolean bereich = vorher != null && vorher.art() == feld.art() && feld.art() != MobilSeite.FeldArt.TEXT;
            if (bereich) {
                // von – bis: zwei Zeitfelder derselben Art sind ein Bereich.
                b.append("<span class=\"w-dash\">–</span>");
            }
            b.append("<input class=\"w-field").append(feld.art() == MobilSeite.FeldArt.TEXT ? "" : " w-time")
                    .append("\" type=\"").append(feld.art().typ()).append("\" name=\"").append(FELD_PRAEFIX)
                    .append(feld.name()).append("\" value=\"").append(e(feld.wert())).append("\" aria-label=\"")
                    .append(e(feld.beschriftung())).append("\">");
            vorher = feld;
        }
        b.append("<noscript><button type=\"submit\" class=\"w-btn w-btn-small\">OK</button></noscript>")
                .append(FORMULAR_ZU);
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

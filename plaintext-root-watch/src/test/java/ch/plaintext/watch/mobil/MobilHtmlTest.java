/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.mobil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Karte 1355: was {@link MobilHtml} fuer <b>jede</b> Seite verspricht — Escaping, CSP ohne
 * Inline-Skript, CSRF in jedem Formular und nur Klassen aus {@code watch.css}.
 *
 * <p>Jede Aussage hat ihre Positivkontrolle: das Escaping wird mit einem Wert geprueft, der
 * ohne Escaping ein ausfuehrbares {@code <script>} waere, und die Klassenpruefung verlangt,
 * dass sie ueberhaupt Klassen findet.</p>
 */
class MobilHtmlTest {

    private static final String BOESE = "<script>alert(1)</script>\"'&";

    private static final MobilHtml.Formular FORMULAR = new MobilHtml.Formular(
            "/watch/m/test", "/watch/m/test/", "_csrf", "tok-123", null);

    private static MobilSeite alles(String text) {
        return MobilSeite.neu()
                .wert(text, text, text)
                .knoepfe(text, List.of(new MobilSeite.Knopf("erfassen", text, text),
                        new MobilSeite.Knopf("zweite", null, "ohne Wert")))
                .liste(text, text, List.of(new MobilSeite.Eintrag("7", text, text, "loeschen"),
                        new MobilSeite.Eintrag("8", "nicht loeschbar", null, null)))
                .hinweis(text)
                .bauen();
    }

    private static String ganzeSeite(String inhalt) {
        return MobilHtml.seite(new MobilHtml.Rahmen("Titel", "2/5", "/watch/zeit.html",
                "/watch/elemente.html", "/watch/m/_/watch.css?v=a", "/watch/m/_/mobil.js?v=b", "Erfasst", false), inhalt);
    }

    @Test
    @DisplayName("Jeder Text wird escaped — ein <script> aus den Daten wird nie zu Markup")
    void allesWirdEscaped() {
        String html = MobilHtml.inhalt(alles(BOESE), FORMULAR);

        assertThat(html).doesNotContain("<script>").doesNotContain("\"'&<");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;&quot;&#39;&amp;");
        // Positivkontrolle: der Text kommt wirklich in allen Bausteinen an (Wert, Label, Hinweis,
        // Knopf, Liste) — sonst waere "kein <script>" auch fuer eine leere Seite wahr.
        assertThat(html.split("&lt;script&gt;", -1).length - 1).isGreaterThanOrEqualTo(8);
    }

    @Test
    @DisplayName("CSP: kein Inline-Skript, kein on…-Attribut, kein style-Attribut, kein javascript:")
    void keinInlineSkript() {
        String html = ganzeSeite(MobilHtml.inhalt(alles("x"), FORMULAR));

        // Genau ein <script>, und das hat src und keinen Rumpf.
        Matcher m = Pattern.compile("<script([^>]*)>(.*?)</script>", Pattern.DOTALL).matcher(html);
        int anzahl = 0;
        while (m.find()) {
            anzahl++;
            assertThat(m.group(1)).contains("src=");
            assertThat(m.group(2)).isEmpty();
        }
        assertThat(anzahl).isEqualTo(1);
        assertThat(html).doesNotContainPattern("\\son[a-z]+\\s*=");
        assertThat(html).doesNotContain(" style=").doesNotContain("javascript:");
    }

    @Test
    @DisplayName("Jedes POST-Formular traegt das CSRF-Feld, jedes GET-Formular nicht")
    void csrfInJedemPost() {
        String html = MobilHtml.inhalt(alles("x"), FORMULAR);

        Matcher m = Pattern.compile("<form([^>]*)>(.*?)</form>", Pattern.DOTALL).matcher(html);
        int post = 0;
        int get = 0;
        while (m.find()) {
            boolean istPost = m.group(1).contains("method=\"post\"");
            if (istPost) {
                post++;
                assertThat(m.group(2)).contains("name=\"_csrf\" value=\"tok-123\"");
            } else {
                get++;
                // Ein GET-Formular schriebe den Token in die Adresse — ins Zugriffslog und in den
                // Verlauf. Genau das darf nie passieren.
                assertThat(m.group(2)).doesNotContain("_csrf");
            }
        }
        assertThat(post).as("Knopfreihe + Loeschbestaetigung").isEqualTo(2);
        assertThat(get).as("zwei Listenzeilen").isEqualTo(2);
    }

    @Test
    @DisplayName("Ohne CSRF-Token in der Anwendung faellt nur das Feld weg, nicht das Formular")
    void ohneCsrf() {
        String html = MobilHtml.inhalt(alles("x"),
                new MobilHtml.Formular("/watch/m/test", "/watch/m/test/", null, null, null));
        assertThat(html).doesNotContain("_csrf").contains("method=\"post\"");
    }

    @Test
    @DisplayName("Die Rueckfrage beim Loeschen ist zu — ausser fuer den Eintrag aus ?frage=")
    void rueckfrage() {
        String zu = MobilHtml.inhalt(alles("x"), FORMULAR);
        assertThat(zu).contains("id=\"m-frage-0\" hidden");

        String offen = MobilHtml.inhalt(alles("x"),
                new MobilHtml.Formular("/watch/m/test", "/watch/m/test/", "_csrf", "t", "7"));
        assertThat(offen).contains("id=\"m-frage-0\"><div class=\"w-confirm-text\">");
        // Nur ein Eintrag ist loeschbar: der zweite traegt weder × noch Rueckfrage.
        assertThat(offen).doesNotContain("m-frage-1");
    }

    @Test
    @DisplayName("Knoepfe: jede Aktion an ihre eigene Adresse, der Wert im Knopf")
    void knoepfe() {
        String html = MobilHtml.inhalt(alles("x"), FORMULAR);
        assertThat(html).contains("formaction=\"/watch/m/test/erfassen\" name=\"wert\" value=\"x\"");
        assertThat(html).contains("formaction=\"/watch/m/test/zweite\">ohne Wert</button>");
    }

    @Test
    @DisplayName("Rahmen: Titel, Position, Weiter mit langem Druck, Statuszeile")
    void rahmen() {
        String html = ganzeSeite("");
        assertThat(html).contains("<h1>Titel</h1>").contains("<span class=\"w-pos\">2/5</span>")
                .contains("href=\"/watch/zeit.html\" data-lang-ziel=\"/watch/elemente.html\"")
                .contains("lang drücken: Übersicht")
                .contains("role=\"status\" aria-live=\"polite\">Erfasst</div>")
                .contains("<meta name=\"referrer\" content=\"no-referrer\">");

        String ohne = MobilHtml.seite(new MobilHtml.Rahmen("T", "", null, null, "/a.css", "/b.js", null, false), "");
        assertThat(ohne).doesNotContain("w-nav").contains("aria-live=\"polite\" hidden>");
    }

    @Test
    @DisplayName("Jede benutzte w-Klasse steht in watch.css")
    void nurKlassenAusWatchCss() throws IOException {
        Path css = Path.of("src/main/resources/META-INF/resources/watch/watch.css");
        if (!Files.exists(css)) {
            css = Path.of("plaintext-root-watch").resolve(css);
        }
        Set<String> definiert = treffer(Pattern.compile("\\.(w-[a-z0-9-]+)"), Files.readString(css));

        String html = ganzeSeite(MobilHtml.inhalt(alles("x"), FORMULAR));
        Set<String> genutzt = treffer(Pattern.compile("class=\"([^\"]*)\""), html).stream()
                .flatMap(k -> java.util.Arrays.stream(k.split("\\s+")))
                .filter(k -> k.startsWith("w-"))
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));

        assertThat(genutzt).as("Positivkontrolle").contains("w-card", "w-chip", "w-entry", "w-aktion");
        assertThat(definiert).containsAll(genutzt);
    }

    private static Set<String> treffer(Pattern p, String text) {
        Set<String> t = new TreeSet<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            t.add(m.group(1));
        }
        return t;
    }
}

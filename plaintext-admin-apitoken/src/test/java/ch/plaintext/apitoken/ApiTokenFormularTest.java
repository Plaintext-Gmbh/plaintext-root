/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.apitoken;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 504: Die Ausstellungsmaske zeigte einen Zugriffsumfang an und stellte einen anderen aus.
 *
 * <p><b>Was passiert war:</b> Der Erstellen-Knopf trug
 * {@code process="@this,:fm:tokenName,:fm:tokenValidity"} — ohne {@code tokenScope}. Ein Feld, das
 * PrimeFaces nicht verarbeitet, durchläuft den JSF-Lebenszyklus nicht; sein Wert kommt in der Bean
 * nie an. {@code newTokenScope} behielt also seinen Initialwert {@code READ}, während die
 * Gültigkeit — die in der Liste stand — sauber ankam. Genau deshalb sah der Defekt wie ein
 * Backend-Fehler aus: zwei Tokens mit unterschiedlicher Laufzeit, beide mit {@code READ}, obwohl
 * ADMIN gewählt war. Im Java-Code war nichts falsch.</p>
 *
 * <p>Ein Unit-Test der Bean hätte das nie gefunden — er ruft {@code createToken()} auf, nachdem er
 * {@code newTokenScope} selbst gesetzt hat, und ist immer grün. Der Fehler lebt ausschliesslich in
 * der Verdrahtung der Maske. Deshalb wird hier das XHTML geprüft.</p>
 *
 * <p>Die Regel ist bewusst allgemein: <b>Jedes Eingabefeld dieses Formulars, dessen Wert in die
 * Bean zurückschreibt, muss in der {@code process}-Liste des Knopfes stehen, der es liest.</b> Wer
 * ein viertes Feld ergänzt und die Liste vergisst, fällt hier auf — nicht erst, wenn jemand einen
 * Token mit falschen Rechten in der Hand hält.</p>
 */
class ApiTokenFormularTest {

    private static final Path MASKE = Path.of(
            "src/main/resources/META-INF/resources/api-token.xhtml");

    /** Eingabefelder mit id und Wertbindung: {@code <p:inputText id="x" value="#{bean.y}"}. */
    private static final Pattern EINGABEFELD = Pattern.compile(
            "<p:(?:inputText|selectOneMenu|selectOneRadio|selectBooleanCheckbox|inputNumber|password)\\b"
                    + "[^>]*?\\bid=\"([^\"]+)\"[^>]*?\\bvalue=\"#\\{apiTokenBean\\.(new[^.}]+)\\}\"",
            Pattern.DOTALL);

    private static final Pattern ERSTELLEN_KNOPF = Pattern.compile(
            "<p:commandButton\\b[^>]*?action=\"#\\{apiTokenBean\\.createToken\\(\\)\\}\"[^>]*?>",
            Pattern.DOTALL);

    private static String maske() throws IOException {
        assertTrue(Files.exists(MASKE),
                "Die Maske " + MASKE + " gibt es nicht mehr — wurde sie umbenannt? Dann gehört dieser "
                        + "Test mit, sonst prüft er ins Leere (Karte 504).");
        return Files.readString(MASKE, StandardCharsets.UTF_8);
    }

    /** Ohne diese Gegenprobe wäre der Test auch grün, wenn die Regex gar nichts fände. */
    @Test
    void dieMaskeHatUeberhauptEingabefelderUndEinenErstellenKnopf() throws IOException {
        String xhtml = maske();
        Set<String> felder = eingabefelder(xhtml);
        assertTrue(felder.size() >= 3,
                "Erwartet werden mindestens die drei Felder Name, Gültigkeit und Zugriff — gefunden: "
                        + felder);
        assertTrue(ERSTELLEN_KNOPF.matcher(xhtml).find(),
                "Der Knopf mit action=\"#{apiTokenBean.createToken()}\" wurde nicht gefunden. "
                        + "Wurde er umbenannt, prüft dieser Test nichts mehr.");
    }

    @Test
    void jedesEingabefeldStehtInDerProcessListeDesErstellenKnopfs() throws IOException {
        String xhtml = maske();
        Matcher knopf = ERSTELLEN_KNOPF.matcher(xhtml);
        assertTrue(knopf.find(), "Erstellen-Knopf nicht gefunden");
        String tag = knopf.group();

        Matcher p = Pattern.compile("\\bprocess=\"([^\"]*)\"").matcher(tag);
        assertTrue(p.find(),
                "Der Erstellen-Knopf hat kein process-Attribut. Ohne Angabe verarbeitet PrimeFaces "
                        + "das ganze Formular — das wäre in Ordnung; steht dort aber eine Liste, muss "
                        + "sie vollständig sein (Karte 504).");
        String process = p.group(1);

        Set<String> fehlend = new LinkedHashSet<>();
        for (String feld : eingabefelder(xhtml)) {
            if (!process.contains(feld)) {
                fehlend.add(feld);
            }
        }
        assertTrue(fehlend.isEmpty(),
                "Diese Eingabefelder fehlen in der process-Liste des Erstellen-Knopfs:\n  "
                        + String.join("\n  ", fehlend)
                        + "\n\nprocess=\"" + process + "\"\n\n"
                        + "Ein Feld, das nicht verarbeitet wird, kommt in der Bean nie an — sie behält "
                        + "ihren Initialwert. Genau so wurden am 03.08.2026 zwei Tokens mit READ "
                        + "ausgestellt, obwohl ADMIN gewählt war (Karte 504).");
    }

    /**
     * Der Zugriffsumfang im Besonderen: Er entscheidet, was ein Token darf. Eine eigene Prüfung,
     * damit die Regressionsstelle beim Namen genannt ist, auch wenn die allgemeine Regel oben
     * einmal umgebaut wird.
     */
    @Test
    void derZugriffsumfangWirdVerarbeitet() throws IOException {
        String xhtml = maske();
        Matcher knopf = ERSTELLEN_KNOPF.matcher(xhtml);
        assertTrue(knopf.find());
        Matcher p = Pattern.compile("\\bprocess=\"([^\"]*)\"").matcher(knopf.group());
        assertTrue(p.find());
        assertTrue(p.group(1).contains("tokenScope"),
                "tokenScope fehlt in der process-Liste. Dann stellt die Maske immer den Initialwert "
                        + "der Bean aus (READ), egal was gewählt wurde — der Defekt aus Karte 504.");
    }

    /**
     * Gegenprobe zur Bean: Der Initialwert ist der Wert, der bei diesem Fehler stillschweigend
     * ausgeliefert wurde. Er muss der <b>engste</b> Scope bleiben — fiele hier je ADMIN hin, wäre
     * derselbe Verdrahtungsfehler nicht mehr harmlos, sondern eine Rechteausweitung.
     */
    @Test
    void derInitialwertIstDerEngsteScope() {
        ApiTokenBackingBean bean = new ApiTokenBackingBean();
        assertTrue("READ".equals(bean.getNewTokenScope()),
                "Der Initialwert von newTokenScope ist '" + bean.getNewTokenScope()
                        + "'. Er muss der engste Scope sein: Er ist der Wert, der ausgeliefert wird, "
                        + "wenn das Feld einmal nicht ankommt (Karte 504).");
        assertFalse("ADMIN".equals(bean.getNewTokenScope()), "ADMIN darf nie Vorgabewert sein.");
    }

    private static Set<String> eingabefelder(String xhtml) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher m = EINGABEFELD.matcher(xhtml);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }
}

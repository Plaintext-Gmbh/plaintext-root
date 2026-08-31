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
 * Card 504: The token issuing form displayed one access scope and issued a different one.
 *
 * <p><b>What had happened:</b> The create button carried
 * {@code process="@this,:fm:tokenName,:fm:tokenValidity"} — without {@code tokenScope}. A field that
 * PrimeFaces does not process does not run through the JSF lifecycle; its value never arrives in
 * the bean. {@code newTokenScope} therefore kept its initial value {@code READ}, while the
 * validity — which was in the list — arrived properly. That is exactly why the defect looked like a
 * backend error: two tokens with different lifetimes, both with {@code READ}, although
 * ADMIN had been selected. Nothing was wrong in the Java code.</p>
 *
 * <p>A unit test of the bean would never have found this — it calls {@code createToken()} after
 * having set {@code newTokenScope} itself, and is always green. The bug lives exclusively in
 * the wiring of the form. That is why the XHTML is checked here.</p>
 *
 * <p>The rule is deliberately general: <b>Every input field of this form whose value writes back
 * into the bean must appear in the {@code process} list of the button that reads it.</b> Whoever
 * adds a fourth field and forgets the list is caught here — not only once somebody is holding a
 * token with the wrong permissions.</p>
 */
class ApiTokenFormularTest {

    private static final Path MASKE = Path.of(
            "src/main/resources/META-INF/resources/api-token.xhtml");

    /** Input fields with id and value binding: {@code <p:inputText id="x" value="#{bean.y}"}. */
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

    /** Without this counter-check the test would be green even if the regex found nothing at all. */
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
     * The access scope in particular: it decides what a token is allowed to do. A dedicated check,
     * so that the regression site is named explicitly, even if the general rule above is
     * restructured some day.
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
     * Counter-check on the bean: the initial value is the value that was silently issued during
     * this defect. It must remain the <b>narrowest</b> scope — should ADMIN ever end up here, the
     * same wiring error would no longer be harmless but a privilege escalation.
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

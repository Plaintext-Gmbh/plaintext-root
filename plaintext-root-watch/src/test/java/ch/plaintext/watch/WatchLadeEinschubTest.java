/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1253: Das Vorlagenblatt muss den Einschub {@code laden} tragen, und zwar innerhalb
 * von {@code f:metadata}.
 *
 * <h2>Warum ein Test fuer eine einzelne Zeile</h2>
 *
 * <p>Ohne diesen Einschub kann keine Modulseite ihre Daten laden. Eine Seite, die ein Template
 * einbindet, liefert nur {@code ui:define}-Bloecke — ihr eigenes {@code f:metadata} wird von
 * JSF nicht beachtet, weil die Metadaten der View-Wurzel gelten, und das ist dieses Blatt.
 * Faellt die Zeile bei einem Umbau weg, laufen alle Watch-Seiten weiter, zeigen aber dauerhaft
 * leere Listen: der Fehler, mit dem die Kalender-Seite seit Karte 1248 ausgeliefert wurde, und
 * den weder Bau noch Deploy sichtbar machen.</p>
 *
 * <p>Die Lage innerhalb von {@code f:metadata} wird mitgeprueft, nicht nur das Vorkommen der
 * Zeichenkette: ausserhalb waere der Einschub genauso wirkungslos wie gar keiner — siehe die
 * Gegenprobe unten.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class WatchLadeEinschubTest {

    /** Der Block zwischen den beiden f:metadata-Tags, ueber Zeilen hinweg. */
    private static final Pattern METADATA = Pattern.compile(
            "<f:metadata>(.*?)</f:metadata>", Pattern.DOTALL);

    private static final Pattern EINSCHUB = Pattern.compile(
            "<ui:insert\\s+name=\"laden\"\\s*/>");

    private static Path frame() {
        Path p = Path.of("src/main/resources/META-INF/resources/watch/frame.xhtml");
        return Files.exists(p) ? p : Path.of("plaintext-root-watch").resolve(p);
    }

    private static String metadatenBlock(String blatt) {
        Matcher m = METADATA.matcher(blatt);
        return m.find() ? m.group(1) : "";
    }

    @Test
    @DisplayName("frame.xhtml traegt den Einschub 'laden' innerhalb von f:metadata")
    void einschubVorhandenUndAmRichtigenOrt() throws IOException {
        Path datei = frame();
        assertTrue(Files.exists(datei), "Vorlagenblatt nicht gefunden: " + datei.toAbsolutePath());
        String blatt = Files.readString(datei);

        String block = metadatenBlock(blatt);
        assertFalse(block.isBlank(), "Kein f:metadata im Vorlagenblatt — dann laedt gar nichts mehr");
        assertTrue(EINSCHUB.matcher(block).find(),
                "Ohne <ui:insert name=\"laden\"/> in f:metadata bleibt jede Modulseite leer");
    }

    @Test
    @DisplayName("Der Rahmen laedt weiterhin selbst zuerst")
    void rahmenListenerStehtVoran() throws IOException {
        String block = metadatenBlock(Files.readString(frame()));

        int rahmen = block.indexOf("watchFrameBean.seitenaufruf()");
        Matcher m = EINSCHUB.matcher(block);
        assertTrue(m.find(), "Einschub fehlt");

        assertTrue(rahmen >= 0, "Der Rahmen muss seinen eigenen Listener behalten");
        assertTrue(rahmen < m.start(),
                "Der Rahmen bestimmt erst die aktuelle Seite; eine Modulbean, die vorher laedt, "
                        + "arbeitet auf der Seite davor");
    }

    @Test
    @DisplayName("Gegenprobe: ein Einschub ausserhalb von f:metadata gilt nicht als vorhanden")
    void gegenprobeAusserhalbZaehltNicht() {
        String erfunden = """
                <ui:composition>
                    <f:metadata>
                        <f:event type="preRenderView" listener="#{watchFrameBean.seitenaufruf()}"/>
                    </f:metadata>
                    <h:body>
                        <ui:insert name="laden"/>
                    </h:body>
                </ui:composition>
                """;

        assertTrue(EINSCHUB.matcher(erfunden).find(), "Die Zeichenkette kommt vor …");
        assertFalse(EINSCHUB.matcher(metadatenBlock(erfunden)).find(),
                "… aber nicht im Metadaten-Block. Genau das muss der Test unterscheiden, sonst "
                        + "waere er auch gruen, wenn der Einschub an einer wirkungslosen Stelle steht.");
    }
}

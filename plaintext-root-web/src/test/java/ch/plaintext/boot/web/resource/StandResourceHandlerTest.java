/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web.resource;

import jakarta.faces.application.Resource;
import jakarta.faces.application.ResourceHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Karte 1311: die Adresse einer Ressource aendert sich mit der Projektversion.
 *
 * <p>Die Aussage dieser Klasse ist die <b>Adressbildung</b>. Dass unter der neuen Adresse
 * dieselbe Datei ausgeliefert wird und dass sie ueberhaupt in der Seite landet, kann ein
 * Modultest nicht sagen — das misst {@code RessourcenMarkePlaywrightIT} in
 * plaintext-root-webapp mit zwei Abrufen und einer Aenderung dazwischen.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class StandResourceHandlerTest {

    /** Die Adresse, die Mojarra heute fuer watch.css bildet — an PROD abgelesen. */
    private static final String WATCH_CSS = "/jakarta.faces.resource/watch.css.html?ln=watch";

    @AfterEach
    void zuruecksetzen() {
        RessourcenStand.setze("1.713.0");
    }

    @Test
    @DisplayName("Die Adresse traegt die Marke, und eine neue Version ergibt eine andere Adresse")
    void markeAendertDieAdresse() {
        RessourcenStand.setze("1.713.0");
        String vorher = StandResourceHandler.ergaenze(WATCH_CSS, RessourcenStand.stand());

        RessourcenStand.setze("1.714.0");
        String nachher = StandResourceHandler.ergaenze(WATCH_CSS, RessourcenStand.stand());

        assertEquals(WATCH_CSS + "&rev=1.713.0", vorher);
        assertEquals(WATCH_CSS + "&rev=1.714.0", nachher);
        assertNotEquals(vorher, nachher,
                "Genau das war der Fehler: die Adresse lautete nach jedem Release gleich, und "
                        + "der Browser fragte sieben Tage lang nicht nach.");
    }

    @Test
    @DisplayName("Gegenprobe: ohne die Marke ist die Adresse vor und nach dem Release dieselbe")
    void ohneMarkeBleibtDieAdresseGleich() {
        String vorher = StandResourceHandler.ergaenze(WATCH_CSS, "");
        String nachher = StandResourceHandler.ergaenze(WATCH_CSS, null);

        assertEquals(WATCH_CSS, vorher);
        assertEquals(vorher, nachher,
                "Ohne diese Kontrolle belegt der Test oben nichts: er muss den Zustand DAVOR "
                        + "auch wirklich herstellen koennen.");
    }

    @Test
    @DisplayName("Eine Ressource mit eigener Bibliotheksversion bleibt unberuehrt")
    void bibliothekMitEigenerVersionBleibtStehen() {
        String primefaces = "/jakarta.faces.resource/primefaces.css.html?ln=primefaces&v=15.0.15";
        assertEquals(primefaces, StandResourceHandler.ergaenze(primefaces, "1.714.0"),
                "PrimeFaces wiegt ueber ein Megabyte und hat mit v= bereits eine Marke. Sie bei "
                        + "jedem unserer Releases neu laden zu lassen, kostet auf einer "
                        + "Mobilverbindung viel fuer nichts.");
    }

    @Test
    @DisplayName("Eine Adresse ohne Fragezeichen bekommt eines")
    void adresseOhneParameterBekommtFragezeichen() {
        assertEquals("/jakarta.faces.resource/favicon.ico.html?rev=1.714.0",
                StandResourceHandler.ergaenze("/jakarta.faces.resource/favicon.ico.html", "1.714.0"));
    }

    @Test
    @DisplayName("Zweimal angewandt bleibt es bei einer Marke")
    void zweimalAngewandtBleibtEineMarke() {
        String einmal = StandResourceHandler.ergaenze(WATCH_CSS, "1.714.0");
        assertEquals(einmal, StandResourceHandler.ergaenze(einmal, "1.714.0"));
    }

    @Test
    @DisplayName("Der Wrapper reicht null durch und markiert sonst jede erzeugte Ressource")
    void wrapperMarkiertWasErBekommt() {
        ResourceHandler inner = mock(ResourceHandler.class);
        Resource echt = mock(Resource.class);
        when(echt.getRequestPath()).thenReturn(WATCH_CSS);
        when(inner.createResource("watch.css", "watch")).thenReturn(echt);
        when(inner.createResource("fehlt.css", "watch")).thenReturn(null);

        StandResourceHandler handler = new StandResourceHandler(inner);
        RessourcenStand.setze("1.714.0");

        Resource markiert = handler.createResource("watch.css", "watch");
        assertTrue(markiert.getRequestPath().endsWith("&rev=1.714.0"), markiert.getRequestPath());
        assertNull(handler.createResource("fehlt.css", "watch"),
                "Eine nicht gefundene Ressource muss null bleiben — eine Huelle um null "
                        + "verwandelte den klaren Fehler in eine NullPointerException im Renderer.");
    }

    @Test
    @DisplayName("Ein SNAPSHOT bekommt zusaetzlich die Startzeit, sonst sieht der Entwickler nichts")
    void snapshotBekommtDieStartzeit() {
        RessourcenStand.setze("1.714.0-SNAPSHOT");
        String stand = RessourcenStand.stand();
        assertTrue(stand.startsWith("1.714.0-SNAPSHOT-"),
                "In der Entwicklung bleibt die Version ueber hunderte Baue dieselbe. Ohne Zusatz "
                        + "stellte die Marke dort genau den Fehler wieder her: " + stand);
        assertTrue(stand.length() <= RessourcenStand.MAX_LAENGE, stand);
    }

    @Test
    @DisplayName("Eine ungefilterte oder leere Version faellt auf die Startzeit zurueck")
    void unbrauchbareVersionFaelltZurueck() {
        RessourcenStand.setze("");
        String leer = RessourcenStand.stand();
        RessourcenStand.setze(null);
        assertEquals(leer, RessourcenStand.stand());
        assertTrue(leer.matches("[a-z0-9]+"), leer);

        RessourcenStand.setze("@project.version@");
        assertTrue(RessourcenStand.stand().matches("[A-Za-z0-9._-]+"),
                "Wird die Eigenschaft nicht gefiltert, darf trotzdem nichts in die Adresse "
                        + "geraten, das kodiert werden muesste: " + RessourcenStand.stand());
    }
}

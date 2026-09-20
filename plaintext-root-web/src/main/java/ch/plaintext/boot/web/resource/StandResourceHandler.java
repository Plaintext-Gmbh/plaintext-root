/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web.resource;

import jakarta.faces.application.Resource;
import jakarta.faces.application.ResourceHandler;
import jakarta.faces.application.ResourceHandlerWrapper;
import jakarta.faces.application.ResourceWrapper;

/**
 * Haengt {@value #PARAMETER}{@code =<Projektversion>} an jede JSF-Ressourcenadresse — Karte 1311.
 *
 * <p>Die Begruendung, warum es diese Marke braucht und warum weder Versionsordner noch eine
 * kuerzere Frist genuegen, steht bei {@link RessourcenStand}. Hier steht nur, wie sie in die
 * Adresse kommt.
 *
 * <h2>Was der Wrapper genau tut</h2>
 *
 * <p>Jede Ressource, die ueber {@code h:outputStylesheet}, {@code h:outputScript},
 * {@code h:graphicImage} oder {@code #{resource[...]}} entsteht, geht durch
 * {@code createResource}. Der Wrapper legt eine Huelle darum, die einzig
 * {@code getRequestPath()} veraendert:
 *
 * <pre>
 *   vorher:  /jakarta.faces.resource/watch.css.html?ln=watch
 *   nachher: /jakarta.faces.resource/watch.css.html?ln=watch&amp;rev=1.714.0
 * </pre>
 *
 * <p>Ausgeliefert wird danach dieselbe Datei: Mojarras {@code ResourceHandlerImpl} liest aus der
 * Anfrage nur {@code ln} (die Bibliothek) und leitet alles Uebrige aus dem Pfad ab; ein
 * unbekannter Parameter wird ignoriert. Die Frist von sieben Tagen bleibt damit stehen und ist
 * jetzt richtig — unter einer Adresse, die sich bei jedem Release aendert, darf lange
 * zwischengespeichert werden.
 *
 * <h2>Warum nicht auf alles</h2>
 *
 * <p>Ressourcen, deren Adresse schon ein {@code v=} traegt, bleiben unberuehrt. Das sind die
 * Bibliotheken mit eigener Version — vor allem PrimeFaces, dessen Kette ueber ein Megabyte
 * wiegt (an derselben Stelle gemessen: 1 055 KB gegen 56 KB, Karte 1247). Sie aendert sich nur
 * beim Hochziehen von PrimeFaces, und genau dann aendert sich ihr {@code v=} von selbst. Sie
 * bei jedem Release unserer Anwendung neu herunterladen zu lassen, waere auf einer
 * Mobilverbindung teuer bezahlt fuer nichts.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public class StandResourceHandler extends ResourceHandlerWrapper {

    /**
     * Der Parametername. Bewusst <b>nicht</b> {@code v}: den vergibt JSF selbst fuer die
     * Bibliotheksversion, und eine eigene Marke unter demselben Namen waere von dieser nicht
     * mehr zu unterscheiden.
     */
    public static final String PARAMETER = "rev";

    /**
     * @param wrapped der vorige Handler der Kette
     */
    public StandResourceHandler(ResourceHandler wrapped) {
        super(wrapped);
    }

    @Override
    public Resource createResource(String resourceName) {
        return markiere(super.createResource(resourceName));
    }

    @Override
    public Resource createResource(String resourceName, String libraryName) {
        return markiere(super.createResource(resourceName, libraryName));
    }

    @Override
    public Resource createResource(String resourceName, String libraryName, String contentType) {
        return markiere(super.createResource(resourceName, libraryName, contentType));
    }

    private static Resource markiere(Resource ressource) {
        return ressource == null ? null : new MarkierteRessource(ressource);
    }

    /**
     * Haengt die Marke an, falls sie fehlt.
     *
     * @param pfad  die Adresse, die JSF gebildet hat
     * @param stand die Marke
     * @return die Adresse mit Marke
     */
    static String ergaenze(String pfad, String stand) {
        if (pfad == null || stand == null || stand.isBlank()) {
            return pfad;
        }
        if (pfad.contains(PARAMETER + "=") || pfad.matches(".*[?&]v=.*")) {
            return pfad;
        }
        return pfad + (pfad.indexOf('?') >= 0 ? '&' : '?') + PARAMETER + '=' + stand;
    }

    /**
     * Eine Ressource, die sich nur in ihrer Adresse von der umhuellten unterscheidet. Inhalt,
     * Kopfzeilen und {@code userAgentNeedsUpdate} bleiben unangetastet — die Marke wirkt im
     * Browser, nicht im Server.
     */
    static final class MarkierteRessource extends ResourceWrapper {

        MarkierteRessource(Resource wrapped) {
            super(wrapped);
        }

        @Override
        public String getRequestPath() {
            return ergaenze(super.getRequestPath(), RessourcenStand.stand());
        }
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.boot.plugins.jsf;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Schaltet {@code FACELETS_SKIP_COMMENTS} ein: XML-Kommentare werden beim Uebersetzen der Seite
 * entfernt, statt zu Komponenten zu werden.
 *
 * <h2>Wogegen das schuetzt</h2>
 *
 * <p>Ein Kommentar in einer Facelets-Seite ist standardmaessig <b>kein</b> Kommentar, sondern eine
 * {@code UIInstruction} im Komponentenbaum. In einem {@code h:panelGrid} belegt er damit eine
 * <b>Zelle</b> wie jede andere Komponente. Ein Raster mit {@code columns="2"} fuellt seine Zeilen
 * der Reihe nach, also verschiebt genau ein Kommentar alles Nachfolgende um eine Zelle: Ab dort
 * steht die Beschriftung in der Eingabespalte und das Eingabefeld in der Beschriftungsspalte.
 *
 * <p><b>Der Fund vom 29.08.2026.</b> In {@code useradmin.xhtml} stand zwischen der Zeile
 * „Benutzername" und der Zeile „Vorname" ein erklaerender Kommentar. Genau ab „Vorname" kippte die
 * Maske: Die Beschriftungen rutschten in die rechte Spalte und um eine Zeile nach unten, die
 * Felder in die linke. Die Maske war benutzbar, aber falsch beschriftet, und niemand sucht die
 * Ursache in einem Kommentar. Ein Scan ueber root, app und guild fand <b>16</b> Raster mit
 * Kommentaren oder blossem {@code <br/>} zwischen den Zellen.
 *
 * <p><b>Warum das hier steht und nicht in jeder Anwendung.</b> Als Servlet-Parameter gesetzt gilt
 * die Einstellung fuer jede App, die auf diesem Modul aufsetzt. Ueber {@code application.yml} waere
 * sie es nicht: app, guild, iot und schuetu bringen eigene mit, und von zwei gleichnamigen Dateien
 * am Klassenpfad gewinnt genau eine. Eine Schutzmassnahme, die je nach Ladereihenfolge wirkt, ist
 * keine.
 *
 * <p><b>Was sich sonst noch aendert.</b> Die Kommentare verlassen den Server nicht mehr. Bisher
 * standen sie im ausgelieferten HTML — samt Kartennummern, Fundstellen und Begruendungen, die fuer
 * Entwickler geschrieben sind und nicht fuer Besucher. Das ist kein Loch, aber auch kein Gewinn,
 * und es kostet bei jeder Auslieferung Bytes.
 *
 * <p>Wer einen Kommentar bewusst im HTML haben will (etwa ein Conditional Comment), schreibt ihn
 * als Ausgabe, nicht als Quelltextkommentar. {@code <ui:remark>} bleibt unabhaengig davon immer
 * serverseitig.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
@Configuration
public class FaceletsKommentarConfig {

    /** Name laut Jakarta Faces 4 ({@code jakarta.faces.FACELETS_SKIP_COMMENTS}). */
    static final String PARAMETER = "jakarta.faces.FACELETS_SKIP_COMMENTS";

    /**
     * Setzt den Parameter, sofern die Anwendung ihn nicht selbst schon gesetzt hat.
     *
     * <p>Die Pruefung ist nicht Zierde: Eine Anwendung, die den Wert bewusst auf {@code false}
     * setzt, soll ihn behalten. Ein Rahmenwerk, das die Entscheidung seiner Consumer stillschweigend
     * ueberschreibt, ist schlimmer als der Fehler, den es verhindern will.
     */
    @Bean
    public ServletContextInitializer faceletsKommentareUeberspringen() {
        return servletContext -> {
            String vorhanden = servletContext.getInitParameter(PARAMETER);
            if (vorhanden != null && !vorhanden.isBlank()) {
                log.info("FACELETS_SKIP_COMMENTS ist bereits auf '{}' gesetzt — bleibt so.", vorhanden);
                return;
            }
            servletContext.setInitParameter(PARAMETER, "true");
        };
    }
}

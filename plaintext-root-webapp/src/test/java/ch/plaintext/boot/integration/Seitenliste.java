/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Die Seiten der laufenden Anwendung, abgeleitet aus dem <b>Klassenpfad</b> — nicht aus einem
 * Verzeichnis dieses Moduls (Karte 1029, uebertragen aus Karte 1012).
 *
 * <p><b>Warum aus dem Klassenpfad.</b> root verteilt seine 53 Views ueber 24 Module; die Webapp
 * selbst bringt nur eine Handvoll mit. Eine im Testcode gepflegte Liste erfasst die uebrigen nie
 * und waechst auch nicht mit, wenn ein Modul eine Seite dazubekommt. Genau in dieser Luecke stand
 * in plaintext-app {@code auszahlungeinstellungen.xhtml} 26 Releases lang als HTTP 500.
 *
 * <p><b>Die Trennung Seite/Baustein</b> ist in allen fuenf Repos einheitlich (in Karte 1012 ueber
 * alle 214 XHTML-Dateien nachgezaehlt): {@code /includes/…} wird per {@code ui:include}
 * eingebunden, {@code META-INF/tags/…} sind Facelets-Tags, {@code template*.xhtml} sind
 * Rahmenseiten. Keines davon ist ueber eine URL erreichbar.
 */
final class Seitenliste {

    private static final String WURZEL = "META-INF/resources/";

    private static final List<String> SEITEN = ermitteln();

    private Seitenliste() {
    }

    /** Alle ueber eine URL erreichbaren Views, sortiert, ohne Doppel. */
    static List<String> alle() {
        return SEITEN;
    }

    private static List<String> ermitteln() {
        List<String> gefunden = new ArrayList<>();
        try {
            Resource[] treffer = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:" + WURZEL + "**/*.xhtml");
            for (Resource r : treffer) {
                String url = r.getURL().toString();
                int i = url.lastIndexOf(WURZEL);
                if (i < 0) {
                    continue;
                }
                String pfad = "/" + url.substring(i + WURZEL.length());
                String datei = pfad.substring(pfad.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                if (pfad.contains("/includes/") || datei.startsWith("template")) {
                    continue;
                }
                if (!gefunden.contains(pfad)) {
                    gefunden.add(pfad);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Klassenpfad nach Views nicht durchsuchbar", e);
        }
        Collections.sort(gefunden);
        return List.copyOf(gefunden);
    }
}

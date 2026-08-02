/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.performance;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Einstellungen der Langsam-Erkennung (Karte 430).
 *
 * <p>Bewusst konfigurierbar statt fest verdrahtet: Was „langsam" heisst, unterscheidet sich je
 * Anwendung und Endpunkt. Ein Dateiupload darf Sekunden brauchen, ein Ajax-Postback nicht.
 *
 * @author plaintext.ch
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "plaintext.performance.slow-request")
public class SlowRequestProperties {

    /** Not-Aus. Bei {@code false} misst der Filter nichts und protokolliert nichts. */
    private boolean enabled = true;

    /** Ab dieser Dauer gilt ein Request als langsam und wird auf WARN protokolliert. */
    private Duration schwelle = Duration.ofSeconds(2);

    /**
     * Mindestabstand zwischen zwei WARN-Meldungen **desselben Pfades**. Ist eine Anwendung
     * dauerhaft langsam, entstuende sonst bei jedem Request eine Zeile — das Log waere unlesbar
     * und die eigentliche Information ginge unter. Unterdrueckte Treffer werden gezaehlt und beim
     * naechsten Mal mitgemeldet, damit nichts still verschwindet.
     */
    private Duration meldeabstand = Duration.ofMinutes(1);

    /**
     * Obergrenze der beobachteten Pfade. Schuetzt gegen unbegrenztes Wachstum, wenn Pfade
     * variabel sind; darueber hinaus wird nur noch gemeldet, ohne zu drosseln.
     */
    private int maxPfade = 500;
}

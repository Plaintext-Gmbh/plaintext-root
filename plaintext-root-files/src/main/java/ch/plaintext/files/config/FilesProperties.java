/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.files.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Einstellungen der persönlichen Dateiablage (Karte 1559). Was im Inventar fest verdrahtet war (Basis-URL je Stufe,
 * Frist im Code-Service, «nur Daniel Marthaler» am MCP), ist hier eine Property.
 */
@Data
@ConfigurationProperties(prefix = "plaintext.files")
public class FilesProperties {

    /** Wurzel des Dateisystem-Speichers; nur ohne eigenes {@code DateiSpeicher}-Bean. */
    private String verzeichnis = System.getProperty("user.home") + "/plaintext-files";

    /** So lange nach der letzten Änderung lebt eine Datei; neu hochladen zählt die Frist neu. */
    private Duration frist = Duration.ofDays(21);

    /** Basis der Freigabe-Links, z.B. {@code https://app.example.ch}; leer = {@code EigeneAdresse}. */
    private String linkBasis = "";

    /**
     * Antwort auf «Wer bist du?», die die MCP-Werkzeuge verlangen, z.B. ein Name; leer = keine Frage. Das ersetzt
     * keine Anmeldung, es hält eine KI davon ab, die Ablage für jemand anderen am selben Client zu öffnen.
     */
    private String mcpIdentitaet = "";

    /** Grösste Datei beim Hochladen. */
    private long maxBytes = 50L * 1024 * 1024;
}

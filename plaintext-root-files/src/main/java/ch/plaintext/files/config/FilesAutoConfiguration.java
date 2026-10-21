/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.files.config;

import ch.plaintext.files.DateiSpeicher;
import ch.plaintext.files.speicher.DateisystemDateiSpeicher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import java.nio.file.Path;

/**
 * Bindet die persönliche Dateiablage (Karte 1559) in jede Anwendung ein, die das Modul auf dem Klassenpfad hat.
 * Die Freigabe-Links gehen über den {@link ch.plaintext.linktoken.LinkTokenStore} (Standard in
 * {@code plaintext-root-freigabe}).
 */
@AutoConfiguration
@EnableConfigurationProperties(FilesProperties.class)
@ComponentScan({"ch.plaintext.files.service", "ch.plaintext.files.web", "ch.plaintext.files.mcp"})
public class FilesAutoConfiguration {

    /** Dateisystem unter {@code plaintext.files.verzeichnis}, solange die Anwendung keinen eigenen Speicher bringt. */
    @Bean
    @ConditionalOnMissingBean(DateiSpeicher.class)
    public DateiSpeicher dateiSpeicher(FilesProperties properties) {
        return new DateisystemDateiSpeicher(Path.of(properties.getVerzeichnis()));
    }
}

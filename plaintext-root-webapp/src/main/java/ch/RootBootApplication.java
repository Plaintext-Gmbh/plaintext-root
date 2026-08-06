/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

// @EnableMethodSecurity aktiviert die Auswertung von @PreAuthorize/@PostAuthorize. Ohne sie werden
// diese Annotationen STILL ignoriert — die Schranke steht im Code und laesst jeden durch. Der Bestand
// hat diese Entscheidung zweimal schriftlich getroffen, solange der Schalter fehlte
// (PlaintextSecurityImpl:501 "wuerde also still wirkungslos annotiert", I18nExportController:49
// "die Annotation waere eine stille Attrappe") und deshalb im Rumpf geprueft. Karte 546.
@SpringBootApplication
@ComponentScan(basePackages = {"ch.plaintext"})
@EntityScan(basePackages = {"ch.plaintext"})
@EnableJpaRepositories(basePackages = {"ch.plaintext"})
@EnableScheduling
@EnableAsync
@EnableMethodSecurity
public class RootBootApplication {
    public static void main(String[] args) {
        SpringApplication.run(RootBootApplication.class, args);
    }
}

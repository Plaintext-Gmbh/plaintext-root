/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules.laufzeit;

import ch.plaintext.lader.ModulAuswahl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Karte 1610: zweite Sperre des Entwicklungsmodus. Der Lader sieht vor dem Start nur Umgebung und
 * System-Property; ein Profil {@code prod} aus einer Konfig-Datei kennt erst Spring. Hat der Lader unsignierte
 * Module geladen ({@link ModulAuswahl#UNSIGNIERT_GELADEN}) und ist {@code prod} aktiv: Start abgebrochen.
 */
@Slf4j
@Component
public class UnsigniertWaechter {

    public UnsigniertWaechter(Environment env) {
        pruefen(System.getProperty(ModulAuswahl.UNSIGNIERT_GELADEN), env);
    }

    static void pruefen(String geladen, Environment env) {
        if (geladen == null) {
            return;
        }
        if (env.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("unsignierte Module (" + geladen + ") mit Profil prod: Start abgebrochen");
        }
        log.warn("!!! ENTWICKLUNGSMODUS: unsignierte Module geladen ({}) - nur lokal (./traeger), NIE auf PROD !!!", geladen);
    }
}

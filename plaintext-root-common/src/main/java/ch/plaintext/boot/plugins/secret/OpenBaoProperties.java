/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration der {@code bao:}-Property-Quelle (Karte 995).
 *
 * <pre>{@code
 * plaintext:
 *   bao:
 *     enabled: true
 *     url: http://openbao:8200        # nur im Docker-Netz erreichbar, kein Ingress
 *     mount: secret                   # KV-v2-Mount
 *     token-file: /run/secrets/bao-token
 * }</pre>
 *
 * <p><b>Der Token gehoert in eine Datei, nicht in die Umgebung.</b> Dieselbe Begruendung wie beim
 * Vaultwarden-Master-Passwort (Karte 942): Eine Umgebungsvariable liest jeder, der {@code printenv}
 * im Container ausfuehren darf, und sie steht zusaetzlich in {@code docker inspect}. {@link #token}
 * existiert nur fuer Tests und Notfaelle und wird beim Start ge-WARN-t.</p>
 *
 * <p><b>Kein Zirkel:</b> Diese Werte duerfen selbst keine {@code bao:}-Referenzen sein — sie sind
 * das Bootstrap. Der Resolver prueft das nicht; er kaeme gar nicht erst so weit, weil er diese
 * Konfiguration braucht, um irgendetwas aufzuloesen.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "plaintext.bao")
public class OpenBaoProperties {

    /** Master-Schalter. Ist {@code false}, wird jede {@code bao:}-Referenz zum Startfehler. */
    private boolean enabled = false;

    /** Basis-URL, ohne abschliessenden Schraegstrich (der wird ohnehin entfernt). */
    private String url = "http://openbao:8200";

    /** Name des KV-v2-Mounts. */
    private String mount = "secret";

    /** Pfad zu der Datei mit dem Zugriffstoken ({@code *_FILE}-Konvention). */
    private String tokenFile;

    /**
     * Token direkt — <b>nur fuer Tests</b>. Im Betrieb {@link #tokenFile} verwenden; ist dieser
     * Wert gesetzt, wird beim Start gewarnt.
     */
    private String token;

    /** Verbindungs-Timeout in Sekunden. */
    private int httpTimeoutSeconds = 10;
}

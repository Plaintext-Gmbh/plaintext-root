/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars;

import java.net.URI;

/**
 * Wie man einen Sidecar erreicht (Karte 1400).
 *
 * @param name     Name des Sidecars
 * @param basisUrl Basis-URL ohne abschliessenden Schrägstrich, z. B. {@code http://messenger-sidecar:3000}
 * @param token    Bearer-Token oder {@code null}, wenn keiner hinterlegt ist
 */
public record SidecarVerbindung(String name, String basisUrl, String token) {

    /** @return die URL eines Pfads des Sidecars, z. B. {@code uri("/bild/vorschau")} */
    public URI uri(String pfad) {
        String p = pfad == null || pfad.isEmpty() ? "" : pfad.startsWith("/") ? pfad : "/" + pfad;
        return URI.create(basisUrl + p);
    }

    /** @return Wert für den Header {@code Authorization} oder {@code null} ohne Token */
    public String authorization() {
        return token == null || token.isEmpty() ? null : "Bearer " + token;
    }

    /** Ohne den Token: ein Record gibt sonst alle Felder aus, auch in Logs und Fehlermeldungen. */
    @Override
    public String toString() {
        return "SidecarVerbindung[name=" + name + ", basisUrl=" + basisUrl + ", token=" + (token == null ? "keiner" : "***") + "]";
    }
}

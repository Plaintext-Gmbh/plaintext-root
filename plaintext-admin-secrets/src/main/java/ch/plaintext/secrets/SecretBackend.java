/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

/**
 * Abstraktion über die Secret-Backends. Bewusst <b>one-way</b>: die Werte selbst werden über dieses
 * Interface NICHT ausgelesen (kein {@code get(value)}) — nur Metadaten gezeigt und gesetzt. Die
 * Auflistung/Verwaltung der verwalteten Secrets macht der {@link SecretService} über {@code secret_entry}
 * (funktioniert so auch für Backends ohne List-API wie Vaultwarden).
 */
public interface SecretBackend {

    SecretBackendType type();

    /** Ist dieses Backend konfiguriert/erreichbar? */
    boolean isAvailable();

    /**
     * Live-Test: greift das Backend gerade, und falls nicht — was fehlt? Macht (wo sinnvoll) einen
     * echten Erreichbarkeits-Check, nicht nur Config-Präsenz. Fürs UI im Backend-Bereich.
     */
    SecretHealth health();

    /** Kommentar/Metadaten zu einem Secret (z.B. Vaultwarden-Notiz), oder {@code null}. */
    String comment(String name);

    /** Secret setzen/anlegen (one-way). {@code note} = optionale Freitext-Notiz. */
    void set(String name, String value, String note);

    /**
     * INTERN — liest den Klartext-Wert eines Secrets. NIE fürs UI; der one-way-Charakter der Anzeige
     * bleibt gewahrt. {@code null}, wenn nicht lesbar.
     *
     * <p>Zulässige Verwender sind die Migration (Backend→Backend-Zügeln) und
     * {@link SecretService#resolve(String)}, über das technische Verbraucher ein gepflegtes Secret zur
     * Laufzeit beziehen.
     */
    String readValue(String name);
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

/**
 * Oeffentliche, fail-safe API fuer Consumer-Apps, um Secrets aus
 * der eigenen Vaultwarden-Instanz zu beziehen.
 *
 * <p><b>Item-Matching:</b> zuerst exakter (case-insensitiver) Name-Treffer;
 * gibt es keinen, greift der erste Item, dessen Name den gesuchten String
 * (case-insensitive) <i>enthaelt</i>. Das erlaubt kurze Suchbegriffe wie
 * {@code "Paperless"} fuer ein Item {@code "Paperless ngx Admin (example.invalid)"}.</p>
 *
 * <p><b>Fail-safe:</b> Ist der Client deaktiviert oder tritt ein Fehler auf,
 * liefern alle Getter {@link Optional#empty()} — es wird NIE eine Exception nach
 * aussen gereicht und NIE der App-Boot gebrochen. Secret-Werte werden nicht
 * geloggt (nur Item-Namen/Booleans).</p>
 *
 * <p><b>Rotation</b> ({@link #rotatePassword(String, String)}): setzt das Passwort
 * eines Items neu (Schreibrichtung, {@code PUT /api/ciphers/{id}}), laesst alle
 * anderen Felder unveraendert und invalidiert danach den Lese-Cache. Ebenfalls
 * fail-safe: bei deaktiviertem Vault oder jedem Fehler {@code false}, nie eine
 * Exception nach aussen.</p>
 */
@Slf4j
public class VaultwardenSecretService {

    private final VaultwardenProperties props;
    private final VaultwardenClient client;

    public VaultwardenSecretService(VaultwardenProperties props, VaultwardenClient client) {
        this.props = props;
        this.client = client;
    }

    /** {@code true}, wenn der Vault-Client per Konfiguration aktiv ist. */
    public boolean isEnabled() {
        return props.isEnabled();
    }

    /** Passwort des benannten Items. */
    public Optional<String> getPassword(String itemName) {
        return findItem(itemName).map(VaultwardenItem::password).filter(v -> v != null && !v.isEmpty());
    }

    /** Benutzername des benannten Items. */
    public Optional<String> getUsername(String itemName) {
        return findItem(itemName).map(VaultwardenItem::username).filter(v -> v != null && !v.isEmpty());
    }

    /** Benutzerdefiniertes Feld des benannten Items (Feldname case-insensitive). */
    public Optional<String> getField(String itemName, String fieldName) {
        return findItem(itemName).flatMap(i -> i.field(fieldName)).filter(v -> v != null && !v.isEmpty());
    }

    /** Das gesamte entschluesselte Item (username/password/fields). */
    public Optional<VaultwardenItem> getSecret(String itemName) {
        return findItem(itemName);
    }

    /**
     * Rotiert (ueberschreibt) das Passwort des benannten Login-Items im Tresor.
     *
     * <p>Nur {@code login.password} wird neu verschluesselt und geschrieben; alle
     * uebrigen Felder bleiben unveraendert. Nach erfolgreichem Schreiben wird der
     * Lese-Cache invalidiert, sodass Folge-{@link #getPassword(String)}-Aufrufe den
     * neuen Wert liefern.</p>
     *
     * <p>Fail-safe: {@code false}, wenn der Vault deaktiviert ist, die Eingaben
     * fehlen oder ein Fehler auftritt — es wird NIE eine Exception geworfen und NIE
     * ein Secret geloggt.</p>
     *
     * @param itemName    Item-Name (Matching wie bei den Gettern: exakt, dann enthaelt)
     * @param newPassword neuer Passwort-Klartext
     * @return {@code true} bei erfolgreichem Schreiben (2xx), sonst {@code false}
     */
    public boolean rotatePassword(String itemName, String newPassword) {
        if (!isEnabled()) {
            log.debug("Vault deaktiviert - Rotation von Item '{}' uebersprungen", itemName);
            return false;
        }
        if (itemName == null || itemName.isBlank() || newPassword == null) {
            return false;
        }
        try {
            return client.rotatePassword(itemName, newPassword);
        } catch (Exception e) {
            // fail-safe: nie nach aussen werfen
            log.warn("Rotation fuer Item '{}' fehlgeschlagen: {}", itemName, e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Boot-Retry-Unterstuetzung (package-private, nur fuer den VaultwardenValueResolver)
    // ------------------------------------------------------------------

    /**
     * {@code true}, wenn der letzte Vault-Zugriff an einer TRANSIENTEN Stoerung scheiterte
     * (Login/Sync-Fehler, Timeout, HTTP 429) — die leere Antwort beweist dann NICHT, dass das
     * Item fehlt. {@code false} nach erfolgreichem Sync: dann ist eine leere Antwort definitiv.
     */
    boolean istLetzterZugriffTransientGescheitert() {
        return client.istLetzterRefreshGescheitert();
    }

    /** {@code true}, wenn der letzte Fehlschlag ein erkanntes Rate-Limit (HTTP 429) war. */
    boolean warLetzterFehlerRateLimit() {
        return client.warLetzterFehlerRateLimit();
    }

    /** Secret-freie Meldung des letzten Fehlschlags; leer nach einem Erfolg. */
    String letzteVaultFehlermeldung() {
        return client.letzteFehlermeldung();
    }

    /**
     * Verwirft den (ggf. leeren) Cache samt Fehler-Backoff, damit der NAECHSTE Zugriff wirklich
     * gegen Vaultwarden geht. Fuer den Boot-Retry im Resolver, der die Wartezeit selbst steuert.
     */
    void erzwingeNeuenVersuch() {
        client.invalidate();
    }

    // ------------------------------------------------------------------

    private Optional<VaultwardenItem> findItem(String itemName) {
        if (!isEnabled()) {
            log.debug("Vault deaktiviert - kein Zugriff auf Item '{}'", itemName);
            return Optional.empty();
        }
        if (itemName == null || itemName.isBlank()) {
            return Optional.empty();
        }
        try {
            List<VaultwardenItem> items = client.getItems();
            String needle = itemName.trim();
            // 1) exakter Name (case-insensitive)
            for (VaultwardenItem i : items) {
                if (i.name() != null && i.name().equalsIgnoreCase(needle)) {
                    return Optional.of(i);
                }
            }
            // 2) enthaelt (case-insensitive)
            String needleLc = needle.toLowerCase(Locale.ROOT);
            for (VaultwardenItem i : items) {
                if (i.name() != null && i.name().toLowerCase(Locale.ROOT).contains(needleLc)) {
                    return Optional.of(i);
                }
            }
            log.debug("Kein Vault-Item passend zu '{}' ({} Items geprueft)", itemName, items.size());
            return Optional.empty();
        } catch (Exception e) {
            // fail-safe: nie nach aussen werfen
            log.warn("Vault-Zugriff fuer Item '{}' fehlgeschlagen: {}", itemName, e.getMessage());
            return Optional.empty();
        }
    }
}

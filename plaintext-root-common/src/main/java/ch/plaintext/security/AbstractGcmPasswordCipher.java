/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.security;

import ch.plaintext.arch.StabileApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Gemeinsame AES-256-GCM-Verschlüsselung für Passwörter "at rest".
 *
 * <p>Diese Basisklasse wurde aus den bis dahin wortgleichen Kopien
 * {@code CaldavPasswordCipher} (kalenderhost) und {@code CarddavPasswordCipher} (kontakte)
 * extrahiert; beide Module hängen ohnehin an {@code plaintext-app-interfaces}. Konkrete
 * Ciphers erben und liefern nur noch Schlüssel und Bezeichnung.</p>
 *
 * <p><b>Fail-fast in PROD, fail-safe in dev</b> (Zustandsbericht 29.08.2026): Fehlt der Key im
 * Spring-Profil {@code prod} (so setzt es das Dockerfile per {@code SPRING_PROFILES_ACTIVE}; alle
 * vier NAS-Instanzen INT/PROD blue/green laufen damit), bricht der Boot mit einer
 * {@link IllegalStateException} ab — bis dahin wurde nur gewarnt und im Klartext gespeichert, und
 * genau deshalb fiel ein vergessenes Secret im Betrieb nie auf. In dev/test bleibt der
 * Klartext-Durchlauf (einmaliges WARN), damit ein lokaler Start ohne Env-Setup funktioniert.
 * Vorbild: {@code resolveRememberMeKey} in {@code PlaintextSecurityConfig} (plaintext-root).</p>
 *
 * <p>Speicherformat: {@link #PREFIX} + Base64(IV[12] || Ciphertext || GCM-Tag[16]), IV je
 * Verschlüsselung frisch zufällig.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@StabileApi("Basis fuer AES-GCM-Passwortverschluesselung (Karte 1504, aus app-interfaces)")
public abstract class AbstractGcmPasswordCipher {

    public static final String PREFIX = "{enc-gcm}";

    private static final Logger log = LoggerFactory.getLogger(AbstractGcmPasswordCipher.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_HEX_LENGTH = 64;

    private final SecretKey key;
    private final String label;
    private final SecureRandom random = new SecureRandom();

    /**
     * Konstruktor ohne Profil-Kenntnis — verhält sich wie dev (fail-safe). Nur für Tests und
     * Nicht-Spring-Kontexte gedacht; Spring-Komponenten nehmen {@link #AbstractGcmPasswordCipher(String,
     * String, boolean)} mit {@link #istProduktion(Environment)}.
     *
     * @param hexKey 64 Hex-Zeichen (256 Bit) oder {@code null}/leer für "Verschlüsselung aus"
     * @param label  fachliche Bezeichnung für Logs und Fehlermeldungen, z.B. {@code "CalDAV"}
     */
    protected AbstractGcmPasswordCipher(String hexKey, String label) {
        this(hexKey, label, false);
    }

    /**
     * @param hexKey     64 Hex-Zeichen (256 Bit) oder {@code null}/leer für "Verschlüsselung aus"
     * @param label      fachliche Bezeichnung für Logs und Fehlermeldungen, z.B. {@code "CalDAV"}
     * @param production {@code true} im Spring-Profil {@code prod}: fehlender Key ist dann ein Boot-Fehler
     */
    protected AbstractGcmPasswordCipher(String hexKey, String label, boolean production) {
        this.label = label;
        if (hexKey == null || hexKey.isBlank()) {
            if (production) {
                throw new IllegalStateException("plaintext.security.data-encryption-key (Env "
                        + "PLAINTEXT_SECURITY_DATA_ENCRYPTION_KEY) ist im Profil prod Pflicht: ohne Key würden "
                        + label + "-Passwörter im KLARTEXT gespeichert. Einen 64-Hex-Zeichen-Key (256 Bit) als "
                        + "Secret provisionieren.");
            }
            this.key = null;
            log.warn("plaintext.security.data-encryption-key ist NICHT gesetzt - {}-Passwörter werden "
                    + "weiterhin im KLARTEXT gespeichert (bisheriges Verhalten, nur ausserhalb von prod). "
                    + "Für at-rest-Verschlüsselung einen 64-Hex-Zeichen-Key (256 Bit) als Secret "
                    + "provisionieren.", label);
        } else {
            this.key = new SecretKeySpec(parseKey(hexKey.trim()), "AES");
            log.info("{}-Passwort-Verschlüsselung (AES-256-GCM) ist aktiv.", label);
        }
    }

    /**
     * Erkennt die Produktivumgebung am aktiven Spring-Profil {@code prod} — dieselbe Regel wie
     * {@code PlaintextSecurityConfig.isProduction} in plaintext-root. {@code null} (kein Spring-Kontext)
     * gilt als dev.
     */
    public static boolean istProduktion(Environment environment) {
        if (environment == null) {
            return false;
        }
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] parseKey(String hex) {
        if (hex.length() != KEY_HEX_LENGTH) {
            throw new IllegalStateException("plaintext.security.data-encryption-key muss genau "
                    + KEY_HEX_LENGTH + " Hex-Zeichen (256 Bit) lang sein, hat aber " + hex.length() + " Zeichen.");
        }
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException _) {
            throw new IllegalStateException(
                    "plaintext.security.data-encryption-key ist kein gültiger Hex-String (0-9a-f erwartet).");
        }
    }

    public boolean isActive() {
        return key != null;
    }

    /**
     * Verschlüsselt einen Klartext-Wert fürs Speichern. Ohne konfigurierten Key wird der Wert unverändert
     * durchgereicht. {@code null}/leer sowie bereits verschlüsselte Werte (Prefix) bleiben unverändert.
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty() || key == null || plain.startsWith(PREFIX)) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertextAndTag = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ciphertextAndTag.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertextAndTag, 0, out, iv.length, ciphertextAndTag.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException(
                    label + "-Passwort konnte nicht verschlüsselt werden (" + e.getClass().getSimpleName() + ").");
        }
    }

    /**
     * Liest einen gespeicherten Wert: mit {@link #PREFIX} wird entschlüsselt, ohne Prefix wird der Wert
     * als Klartext-Altbestand unverändert zurückgegeben.
     */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        if (key == null) {
            throw new IllegalStateException("Verschlüsseltes " + label + "-Passwort gefunden, aber "
                    + "plaintext.security.data-encryption-key ist nicht gesetzt.");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (all.length <= IV_BYTES) {
                throw new IllegalArgumentException("zu kurz");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("{}-Passwort-Entschlüsselung fehlgeschlagen ({}) - falscher Key oder beschädigter Wert?",
                    label, e.getClass().getSimpleName());
            throw new IllegalStateException(label + "-Passwort kann nicht entschlüsselt werden "
                    + "(falscher plaintext.security.data-encryption-key oder beschädigter Wert).");
        }
    }
}

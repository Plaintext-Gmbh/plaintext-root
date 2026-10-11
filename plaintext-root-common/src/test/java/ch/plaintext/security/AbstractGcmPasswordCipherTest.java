/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deckt die gemeinsame AES-256-GCM-Logik ab (Round-Trip, Klartext-Altbestand, Fail-safe ohne Key,
 * Key-Validierung und Fehlerpfade beim Entschlüsseln).
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class AbstractGcmPasswordCipherTest {

    private static final String KEY = "0".repeat(63) + "1";
    private static final String ANDERER_KEY = "f".repeat(64);

    /** Minimale konkrete Ausprägung — die Subklassen im Produktivcode tun genau dasselbe. */
    private static final class TestCipher extends AbstractGcmPasswordCipher {
        private TestCipher(String hexKey) {
            super(hexKey, "Test");
        }

        private TestCipher(String hexKey, String label, boolean production) {
            super(hexKey, label, production);
        }
    }

    @Test
    @DisplayName("Round-Trip: verschlüsselt gespeichert, wieder als Klartext gelesen")
    void roundTrip() {
        TestCipher cipher = new TestCipher(KEY);

        String encrypted = cipher.encrypt("geheim-öäü");

        assertThat(cipher.isActive()).isTrue();
        assertThat(encrypted).startsWith(AbstractGcmPasswordCipher.PREFIX);
        assertThat(encrypted).doesNotContain("geheim");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("geheim-öäü");
    }

    @Test
    @DisplayName("Jede Verschlüsselung nutzt einen frischen IV")
    void frischerIvProAufruf() {
        TestCipher cipher = new TestCipher(KEY);

        assertThat(cipher.encrypt("abc")).isNotEqualTo(cipher.encrypt("abc"));
    }

    @Test
    @DisplayName("Key mit Whitespace wird getrimmt akzeptiert")
    void keyWirdGetrimmt() {
        TestCipher cipher = new TestCipher("  " + KEY + "\n");

        assertThat(cipher.decrypt(cipher.encrypt("x"))).isEqualTo("x");
    }

    @Test
    @DisplayName("encrypt ist idempotent und lässt null/leer unangetastet")
    void encryptIdempotentUndNullSicher() {
        TestCipher cipher = new TestCipher(KEY);
        String encrypted = cipher.encrypt("wert");

        assertThat(cipher.encrypt(encrypted)).isEqualTo(encrypted);
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.encrypt("")).isEmpty();
    }

    @Test
    @DisplayName("decrypt reicht Klartext-Altbestand und null unverändert durch")
    void decryptAltbestand() {
        TestCipher cipher = new TestCipher(KEY);

        assertThat(cipher.decrypt("klartext-alt")).isEqualTo("klartext-alt");
        assertThat(cipher.decrypt(null)).isNull();
    }

    @Test
    @DisplayName("Ohne Key: Klartext-Durchreichung statt Boot-Fail")
    void ohneKeyFailSafe() {
        TestCipher leer = new TestCipher("");
        TestCipher null_ = new TestCipher(null);

        assertThat(leer.isActive()).isFalse();
        assertThat(null_.isActive()).isFalse();
        assertThat(leer.encrypt("wert")).isEqualTo("wert");
        assertThat(leer.decrypt("wert")).isEqualTo("wert");
    }

    @Test
    @DisplayName("Profil prod ohne Key: Boot-Fehler statt Klartext (Zustandsbericht 29.08.2026)")
    void prodOhneKeyFailFast() {
        for (String fehlend : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> new TestCipher(fehlend, "Test", true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("plaintext.security.data-encryption-key")
                    .hasMessageContaining("prod")
                    .hasMessageContaining("Test-Passwörter");
        }
    }

    @Test
    @DisplayName("Profil prod mit Key: normal aktiv")
    void prodMitKeyAktiv() {
        TestCipher cipher = new TestCipher(KEY, "Test", true);

        assertThat(cipher.isActive()).isTrue();
        assertThat(cipher.decrypt(cipher.encrypt("wert"))).isEqualTo("wert");
    }

    @Test
    @DisplayName("Ausserhalb von prod ohne Key: weiterhin fail-safe (nur WARN)")
    void devOhneKeyBleibtFailSafe() {
        TestCipher cipher = new TestCipher(null, "Test", false);

        assertThat(cipher.isActive()).isFalse();
        assertThat(cipher.encrypt("wert")).isEqualTo("wert");
    }

    @Test
    @DisplayName("istProduktion: nur das Spring-Profil 'prod' zählt, Schreibweise egal, null = dev")
    void istProduktionErkenntNurProdProfil() {
        assertThat(AbstractGcmPasswordCipher.istProduktion(null)).isFalse();
        assertThat(AbstractGcmPasswordCipher.istProduktion(new MockEnvironment())).isFalse();
        assertThat(AbstractGcmPasswordCipher.istProduktion(mitProfilen("dev"))).isFalse();
        assertThat(AbstractGcmPasswordCipher.istProduktion(mitProfilen("prod"))).isTrue();
        assertThat(AbstractGcmPasswordCipher.istProduktion(mitProfilen("PROD"))).isTrue();
        assertThat(AbstractGcmPasswordCipher.istProduktion(mitProfilen("dev", "prod"))).isTrue();
        assertThat(AbstractGcmPasswordCipher.istProduktion(mitProfilen("production"))).isFalse();
    }

    private static MockEnvironment mitProfilen(String... profile) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profile);
        return env;
    }

    @Test
    @DisplayName("Ohne Key, aber verschlüsselter Bestand: klarer Fehler statt stiller Datenverlust")
    void ohneKeyAberVerschluesselterWert() {
        String encrypted = new TestCipher(KEY).encrypt("wert");

        assertThatThrownBy(() -> new TestCipher(null).decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nicht gesetzt");
    }

    @Test
    @DisplayName("Key mit falscher Länge wird abgelehnt")
    void keyFalscheLaenge() {
        assertThatThrownBy(() -> new TestCipher("abcd"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("64 Hex-Zeichen");
    }

    @Test
    @DisplayName("Key mit Nicht-Hex-Zeichen wird abgelehnt")
    void keyKeinHex() {
        assertThatThrownBy(() -> new TestCipher("z".repeat(64)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Hex-String");
    }

    @Test
    @DisplayName("Falscher Key beim Entschlüsseln -> IllegalStateException")
    void falscherKey() {
        String encrypted = new TestCipher(KEY).encrypt("wert");

        assertThatThrownBy(() -> new TestCipher(ANDERER_KEY).decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kann nicht entschlüsselt werden");
    }

    @Test
    @DisplayName("Beschädigter/zu kurzer Wert -> IllegalStateException")
    void beschaedigterWert() {
        TestCipher cipher = new TestCipher(KEY);
        String zuKurz = AbstractGcmPasswordCipher.PREFIX + Base64.getEncoder().encodeToString(new byte[4]);

        assertThatThrownBy(() -> cipher.decrypt(zuKurz))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kann nicht entschlüsselt werden");
        assertThatThrownBy(() -> cipher.decrypt(AbstractGcmPasswordCipher.PREFIX + "kein-base64!!"))
                .isInstanceOf(IllegalStateException.class);
    }
}

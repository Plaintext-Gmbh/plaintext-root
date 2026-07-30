/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

class EncStringTest {

    private static String b64(int n, int fill) {
        byte[] b = new byte[n];
        java.util.Arrays.fill(b, (byte) fill);
        return Base64.getEncoder().encodeToString(b);
    }

    @Test
    void parsesType2WithIvCtMac() {
        String s = "2." + b64(16, 1) + "|" + b64(32, 2) + "|" + b64(32, 3);
        EncString enc = EncString.parse(s);
        assertThat(enc.type()).isEqualTo(2);
        assertThat(enc.isSymmetric()).isTrue();
        assertThat(enc.iv()).hasSize(16);
        assertThat(enc.ct()).hasSize(32);
        assertThat(enc.mac()).hasSize(32);
    }

    @Test
    void parsesType4RsaCtOnly() {
        String s = "4." + b64(256, 7);
        EncString enc = EncString.parse(s);
        assertThat(enc.type()).isEqualTo(4);
        assertThat(enc.isRsa()).isTrue();
        assertThat(enc.isSymmetric()).isFalse();
        assertThat(enc.iv()).isNull();
        assertThat(enc.ct()).hasSize(256);
        assertThat(enc.mac()).isNull();
    }

    @Test
    void parsesLegacyWithoutTypePrefixAsType2WhenMacPresent() {
        String s = b64(16, 1) + "|" + b64(32, 2) + "|" + b64(32, 3);
        EncString enc = EncString.parse(s);
        assertThat(enc.type()).isEqualTo(2);
        assertThat(enc.mac()).hasSize(32);
    }

    @Test
    void rejectsEmpty() {
        assertThatThrownBy(() -> EncString.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EncString.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesType0IvCtWithoutMac() {
        String s = "0." + b64(16, 1) + "|" + b64(32, 2);
        EncString enc = EncString.parse(s);
        assertThat(enc.type()).isZero();
        assertThat(enc.isSymmetric()).isFalse();
        assertThat(enc.isRsa()).isFalse();
        assertThat(enc.iv()).hasSize(16);
        assertThat(enc.ct()).hasSize(32);
        assertThat(enc.mac()).isNull();
    }

    @Test
    void parsesType1IvCtMac() {
        String s = "1." + b64(16, 1) + "|" + b64(16, 2) + "|" + b64(32, 3);
        EncString enc = EncString.parse(s);
        assertThat(enc.type()).isEqualTo(1);
        assertThat(enc.isSymmetric()).isTrue();
        assertThat(enc.isRsa()).isFalse();
        assertThat(enc.mac()).hasSize(32);
    }

    @Test
    void parsesType3RsaCtOnly() {
        EncString enc = EncString.parse("3." + b64(256, 5));
        assertThat(enc.type()).isEqualTo(3);
        assertThat(enc.isRsa()).isTrue();
        assertThat(enc.iv()).isNull();
        assertThat(enc.mac()).isNull();
    }

    @Test
    void parsesType5RsaCtWithMac() {
        EncString enc = EncString.parse("5." + b64(256, 5) + "|" + b64(32, 6));
        assertThat(enc.type()).isEqualTo(5);
        assertThat(enc.isRsa()).isTrue();
        assertThat(enc.iv()).isNull();
        assertThat(enc.ct()).hasSize(256);
        assertThat(enc.mac()).hasSize(32);
    }

    @Test
    void parsesType6RsaCtWithMac() {
        EncString enc = EncString.parse("6." + b64(256, 5) + "|" + b64(32, 6));
        assertThat(enc.type()).isEqualTo(6);
        assertThat(enc.isRsa()).isTrue();
        assertThat(enc.mac()).hasSize(32);
    }

    @Test
    void parsesLegacyWithoutTypePrefixAsType0WhenNoMac() {
        // Nur ein Pipe (iv|ct) und kein Typ-Prefix → type 0.
        String s = b64(16, 1) + "|" + b64(32, 2);
        EncString enc = EncString.parse(s);
        assertThat(enc.type()).isZero();
        assertThat(enc.mac()).isNull();
    }

    @Test
    void rejectsUnknownType() {
        String unknownType = "9." + b64(16, 1);
        assertThatThrownBy(() -> EncString.parse(unknownType))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unbekannter EncString-Typ");
    }

    // ── equals / hashCode / toString ──────────────────────────────────────────

    @Test
    void equalsUndHashCodeVergleichenArrayInhalt() {
        String s = "2." + b64(16, 1) + "|" + b64(32, 2) + "|" + b64(32, 3);
        EncString a = EncString.parse(s);
        EncString b = EncString.parse(s);
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        // Reflexivitaet (this == o Kurzschluss im equals) direkt auf dem Vertrag pruefen.
        assertThat(a.equals(a)).isTrue();
    }

    @Test
    void ungleichBeiAbweichendemInhalt() {
        EncString a = EncString.parse("2." + b64(16, 1) + "|" + b64(32, 2) + "|" + b64(32, 3));
        EncString b = EncString.parse("2." + b64(16, 1) + "|" + b64(32, 2) + "|" + b64(32, 9));
        // Fremdtyp ueber eine Object-Referenz, damit AssertJ keinen Vergleich unaehnlicher Typen
        // moniert; anderer Inhalt, Fremdtyp und null sind alle ungleich (eine Assertion-Kette).
        Object fremderTyp = "string";
        assertThat(a).isNotEqualTo(b).isNotEqualTo(fremderTyp).isNotNull();
    }

    @Test
    void toStringLeaktKeineRohBytes() {
        String payload = b64(16, 1);
        EncString enc = EncString.parse("0." + payload + "|" + b64(32, 2));
        String str = enc.toString();
        // Der Base64-Payload darf NICHT im toString auftauchen.
        assertThat(str)
                .contains("type=0").contains("iv=16B").contains("ct=32B").contains("mac=null")
                .doesNotContain(payload);
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.helpers;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA-Converter: TOTP-Secret verschluesselt at rest (siehe {@link TotpSecretCrypto}).
 * Bewusst nicht {@code autoApply}: nur die eine Spalte in {@code MyUserEntity}.
 *
 * @author info@plaintext.ch
 * @since 1.636.0
 */
@Converter
public class TotpSecretConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return TotpSecretCrypto.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return TotpSecretCrypto.decrypt(dbData);
    }
}

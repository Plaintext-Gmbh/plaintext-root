/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.helpers;

import ch.plaintext.framework.XstreamBaseJPAConverter;
import jakarta.persistence.Converter;

import java.util.Set;

/**
 * JPA converter for the (already hashed) TOTP recovery codes.
 *
 * <p>Analogously to {@link MyUserSetConverter} the codes are serialized as XStream XML into a
 * single VARCHAR column. Deliberately NOT {@code autoApply=true}: the
 * recovery code set is annotated explicitly on the entity with {@code @Convert}, so that
 * the mapping stays unambiguous and does not accidentally affect other {@code Set<String>}
 * fields.
 *
 * <p>Basisklasse seit Karte 1069 (A-02) in root-common -- vorher eine eigene Kopie
 * (MyUserXstreamBaseJPAConverter) hier im Modul.
 */
@Converter
public class RecoveryCodesConverter extends XstreamBaseJPAConverter<Set<String>> {

}

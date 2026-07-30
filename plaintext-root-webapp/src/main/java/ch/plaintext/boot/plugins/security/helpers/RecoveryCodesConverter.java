/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.helpers;

import jakarta.persistence.Converter;

import java.util.Set;

/**
 * JPA-Converter fuer die (bereits gehashten) TOTP-Recovery-Codes.
 *
 * <p>Analog zu {@link MyUserSetConverter} werden die Codes als XStream-XML in eine
 * einzelne VARCHAR-Spalte serialisiert. Bewusst NICHT {@code autoApply=true}: der
 * Recovery-Code-Set wird explizit per {@code @Convert} an der Entity annotiert, damit
 * die Zuordnung eindeutig bleibt und nicht versehentlich andere {@code Set<String>}-
 * Felder betrifft.
 */
@Converter
public class RecoveryCodesConverter extends MyUserXstreamBaseJPAConverter<Set<String>> {

}

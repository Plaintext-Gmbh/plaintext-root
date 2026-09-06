/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.jpa;

import ch.plaintext.framework.XstreamBaseJPAConverter;
import jakarta.persistence.Converter;

import java.util.List;

// Basisklasse seit Karte 1069 (A-02) in root-common -- vorher eine eigene Kopie hier im Modul.
@Converter(autoApply = true)
public class ListConverter extends XstreamBaseJPAConverter<List<String>> {

}
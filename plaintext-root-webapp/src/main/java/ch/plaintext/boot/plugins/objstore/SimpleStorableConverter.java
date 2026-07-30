/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.objstore;

import ch.plaintext.boot.plugins.jpa.XstreamBaseJPAConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class SimpleStorableConverter extends XstreamBaseJPAConverter<SimpleStorable> {

}
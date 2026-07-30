/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import jakarta.persistence.Converter;

import java.util.List;

@Converter(autoApply = true)
public class StringArrayJPAConverter extends XstreamBaseJPAConverter<List<String>> {

}
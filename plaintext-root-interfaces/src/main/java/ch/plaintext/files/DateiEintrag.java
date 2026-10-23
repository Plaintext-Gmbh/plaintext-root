/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.files;

import ch.plaintext.modules.ModulApi;

import java.time.Instant;

/**
 * An entry of {@link DateiSpeicher#list} (card 1559).
 *
 * @param name      file or folder name without path
 * @param ordner    {@code true} for a folder
 * @param groesse   bytes, 0 for folders
 * @param geaendert last change (= last upload)
 */
@ModulApi(art = ModulApi.Art.DTO)
public record DateiEintrag(String name, boolean ordner, long groesse, Instant geaendert) {
}

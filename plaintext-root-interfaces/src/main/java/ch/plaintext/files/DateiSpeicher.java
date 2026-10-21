/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.files;

import ch.plaintext.modules.ModulApi;

import java.util.List;

/**
 * Where the personal file store of {@code plaintext-root-files} keeps its files (card 1559).
 *
 * <p>Paths are relative and separated by {@code /}, e.g. {@code files/anna/bericht.pdf}; the module only passes
 * cleaned names (no {@code ..}, no control characters). An implementation must still refuse to leave its root.</p>
 *
 * <p><b>Default:</b> {@code plaintext-root-files} stores in the file system under {@code plaintext.files.verzeichnis}
 * and steps back as soon as the application defines its own bean. An application with another store (e.g. a
 * persistent volume behind a service) implements this interface.</p>
 */
@ModulApi(art = ModulApi.Art.SCHNITTSTELLE, stabilitaet = ModulApi.Stabilitaet.NEU)
public interface DateiSpeicher {

    /** Stores the file; an existing file is replaced and counts as changed now. Missing folders are created. */
    void upload(String pfad, byte[] inhalt);

    /** @return the content, {@code null} if the file does not exist */
    byte[] download(String pfad);

    /** @return {@code true} if a file exists at the path */
    boolean exists(String pfad);

    /**
     * @param ordner folder, e.g. {@code files/anna}; a missing folder is an empty list
     * @return files and sub folders directly in the folder, without the folder itself
     */
    List<DateiEintrag> list(String ordner);

    /** @return {@code true} if a file was deleted */
    boolean delete(String pfad);

    /**
     * Does the store delete expired files itself (retention rule outside the application)? Then the module does
     * not clean up; otherwise it deletes files {@code plaintext.files.frist} after their last change.
     */
    default boolean raeumtSelbstAuf() {
        return false;
    }
}

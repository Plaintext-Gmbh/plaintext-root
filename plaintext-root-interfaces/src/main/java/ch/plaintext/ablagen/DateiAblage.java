/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.ablagen;

import ch.plaintext.modules.ModulApi;

import java.io.IOException;
import java.util.List;

/**
 * A configured file store (card 1406), e.g. a folder in Nextcloud.
 *
 * <p>Modules (Draw.io, exports …) store files through it without knowing credentials or the server;
 * ROOT sets the store up under <i>Root → Sidecars</i>. All paths are <b>relative</b> to the path
 * configured there, separated by {@code /}; {@code ..}, absolute paths and empty segments are
 * rejected, so a store never leaves its folder.</p>
 */
@ModulApi(art = ModulApi.Art.SCHNITTSTELLE, stabilitaet = ModulApi.Stabilitaet.NEU)
public interface DateiAblage {

    /** @return name of the store, e.g. {@code nextcloud-drawio} */
    String name();

    /**
     * Writes a file; missing folders are created, an existing file is overwritten.
     *
     * @param inhaltTyp media type, e.g. {@code image/png}; {@code null} = {@code application/octet-stream}
     */
    void schreibe(String pfad, byte[] daten, String inhaltTyp) throws IOException;

    /** @return the content of a file (at most 50 MB) */
    byte[] lies(String pfad) throws IOException;

    /** @return {@code true} if a file or a folder exists at the path */
    boolean existiert(String pfad) throws IOException;

    /** @param ordner relative folder, empty = root of the store; the result does not contain the folder itself */
    List<AblageEintrag> liste(String ordner) throws IOException;

    /** Deletes a file; with Nextcloud it ends up in the trash bin. */
    void loesche(String pfad) throws IOException;

    /**
     * Creates a folder, missing parent folders included; an existing folder is no error (card 1475).
     * Git knows no empty folders: there the folder holds a {@code .gitkeep}, which {@link #liste} hides.
     */
    void legeOrdnerAn(String pfad) throws IOException;

    /**
     * Moves or renames a file or a folder; missing parent folders of the target are created (card 1475).
     *
     * @throws IOException if the source is missing, the target exists or lies inside the source
     */
    void verschiebe(String von, String nach) throws IOException;

    /**
     * Deletes a folder (card 1475); with Nextcloud it ends up in the trash bin.
     *
     * @param rekursiv {@code false} deletes only an empty folder
     * @throws java.nio.file.DirectoryNotEmptyException if the folder is not empty and {@code rekursiv} is false
     */
    void loescheOrdner(String pfad, boolean rekursiv) throws IOException;
}

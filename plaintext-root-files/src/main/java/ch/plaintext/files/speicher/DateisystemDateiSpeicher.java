/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.files.speicher;

import ch.plaintext.files.DateiEintrag;
import ch.plaintext.files.DateiSpeicher;
import ch.plaintext.modules.ModulApiUmsetzung;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Standard-{@link DateiSpeicher} (Karte 1559): Dateien unter einem Wurzelverzeichnis. Geschrieben wird erst in
 * {@code .upload-<zufall>} und dann umbenannt, damit ein abgebrochener Upload nie eine halbe Datei hinterlässt;
 * solche Reste zeigt {@link #list} nicht.
 */
@Slf4j
@ModulApiUmsetzung(beschreibung = "Default store of the personal file store: files below plaintext.files.verzeichnis, "
        + "written to a temporary file and renamed, so an aborted upload never leaves half a file.",
        seiteneffekte = ModulApiUmsetzung.Seiteneffekte.INTERN,
        hinweise = {"A path that leaves the root folder is rejected", "raeumtSelbstAuf is false: the module deletes expired files"},
        beispiele = {"upload(\"files/anna/bericht.pdf\", bytes)", "list(\"files/anna\")"})
public class DateisystemDateiSpeicher implements DateiSpeicher {

    static final String TEMP = ".upload-";

    private final Path wurzel;

    public DateisystemDateiSpeicher(Path wurzel) {
        this.wurzel = wurzel.toAbsolutePath().normalize();
    }

    @Override
    public void upload(String pfad, byte[] inhalt) {
        Path ziel = aufloesen(pfad);
        try {
            Files.createDirectories(ziel.getParent());
            Path temp = ziel.resolveSibling(TEMP + UUID.randomUUID());
            Files.write(temp, inhalt);
            Files.move(temp, ziel, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Nicht gespeichert: " + pfad, e);
        }
    }

    @Override
    public byte[] download(String pfad) {
        Path p = aufloesen(pfad);
        try {
            return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Nicht lesbar: " + pfad, e);
        }
    }

    @Override
    public boolean exists(String pfad) {
        return Files.isRegularFile(aufloesen(pfad));
    }

    @Override
    public List<DateiEintrag> list(String ordner) {
        Path o = ordner == null || ordner.isBlank() ? wurzel : aufloesen(ordner);
        if (!Files.isDirectory(o)) {
            return List.of();
        }
        List<DateiEintrag> ergebnis = new ArrayList<>();
        try (Stream<Path> s = Files.list(o)) {
            for (Path p : s.sorted(Comparator.comparing(Path::toString)).toList()) {
                String name = p.getFileName().toString();
                if (name.startsWith(TEMP)) {
                    continue;
                }
                boolean istOrdner = Files.isDirectory(p);
                ergebnis.add(new DateiEintrag(name, istOrdner, istOrdner ? 0 : Files.size(p),
                        Files.getLastModifiedTime(p).toInstant()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Ordner nicht lesbar: " + ordner, e);
        }
        return ergebnis;
    }

    @Override
    public boolean delete(String pfad) {
        try {
            return Files.deleteIfExists(aufloesen(pfad));
        } catch (IOException e) {
            throw new UncheckedIOException("Nicht gelöscht: " + pfad, e);
        }
    }

    /** @throws IllegalArgumentException wenn der Pfad die Wurzel verlässt */
    Path aufloesen(String pfad) {
        if (pfad == null || pfad.isBlank()) {
            throw new IllegalArgumentException("Leerer Pfad");
        }
        Path p = wurzel.resolve(pfad.replace('\\', '/')).normalize();
        if (!p.startsWith(wurzel) || p.equals(wurzel)) {
            throw new IllegalArgumentException("Pfad ausserhalb des Speichers: " + pfad);
        }
        return p;
    }
}

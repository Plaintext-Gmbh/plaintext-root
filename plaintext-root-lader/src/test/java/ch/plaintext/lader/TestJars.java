/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.lader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Kleine Jars fuer die Lader-Tests (Inhalt egal, es zaehlen die Eintragsnamen). */
final class TestJars {

    /** Klassen in der Test-Huelle: eine eigene, eine aus einer Bibliothek in BOOT-INF/lib. */
    static final String HUELLE_KLASSE = "ch/RootBootApplication.class";
    static final String HUELLE_LIB_KLASSE = "org/example/huelle/InDerHuelle.class";

    private TestJars() {
    }

    /** Zip mit Paaren (Name, Inhalt). */
    static byte[] zip(String... namenUndInhalt) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            for (int i = 0; i < namenUndInhalt.length; i += 2) {
                z.putNextEntry(new ZipEntry(namenUndInhalt[i]));
                z.write(namenUndInhalt[i + 1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return b.toByteArray();
    }

    /** Exec-Jar-Attrappe im Spring-Boot-Aufbau. */
    static Path huelle(Path ziel) throws IOException {
        byte[] lib = zip(HUELLE_LIB_KLASSE, "lib", "META-INF/spring.factories", "erlaubt in der Huelle");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            z.putNextEntry(new ZipEntry("BOOT-INF/classes/" + HUELLE_KLASSE));
            z.write(1);
            z.closeEntry();
            z.putNextEntry(new ZipEntry("BOOT-INF/classes/application.yml"));
            z.write(2);
            z.closeEntry();
            z.putNextEntry(new ZipEntry("BOOT-INF/lib/huelle-lib-1.0.jar"));
            z.write(lib);
            z.closeEntry();
        }
        return Files.write(ziel, b.toByteArray());
    }
}

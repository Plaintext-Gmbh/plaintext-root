/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.lader;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

/**
 * Laufzeit-Module Schritt 2 (Karte 1513, Konzept 1503): bereitet vor dem Start der Huelle die Modul-Jars
 * vor und uebergibt an Spring Boot.
 *
 * <p>Start: {@code java -cp app.jar:plaintext-root-lader.jar ch.plaintext.lader.ModulLader}
 * <ol>
 *   <li>Notfall-Schalter gesetzt ({@code PLAINTEXT_MODULE=aus} oder Datei {@code NOTFALL-AUS} im
 *       Modulverzeichnis) oder keine {@code module.yaml}: Start wie heute ({@code JarLauncher}), keine
 *       Modul-Klasse, kein Netz.</li>
 *   <li>Sonst: jedes Jar muss im Cache mit passendem sha256 liegen oder wird von der festen Quelle geholt
 *       und erst nach bestandener Pruefung in den Cache gelegt. Ein Fehler bricht den Start ab, nie wird
 *       ein Modul still ausgelassen (PFLICHT-Anbieter fehlten sonst erst zur Laufzeit).</li>
 *   <li>{@code loader.path} = genau diese Jars, dann {@code PropertiesLauncher}. Vorher laeuft keine
 *       Modul-Klasse.</li>
 * </ol>
 *
 * <p>Die Datei {@code NOTFALL-AUS} wirkt mit einem einfachen Neustart des einen Containers: anders als
 * eine Umgebungsvariable (env_file wird von {@code docker restart} nicht neu gelesen) braucht sie kein
 * {@code --force-recreate}, also keine Neustart-Kaskade ueber {@code depends_on}.
 */
public final class ModulLader {

    static final String VERZEICHNIS_ENV = "PLAINTEXT_MODULE_VERZEICHNIS";
    static final String STANDARD_VERZEICHNIS = "/data/module";
    static final String NOTFALL_ENV = "PLAINTEXT_MODULE";
    static final String NOTFALL_DATEI = "NOTFALL-AUS";
    static final String KONFIG = "module.yaml";
    static final String KONFIG_VORHER = "module.yaml.vorher";

    /** Manifest-Attribute des Huellen-Exec-Jars (gesetzt im pom von plaintext-root-webapp). */
    static final String ATTR_VERSION = "Plaintext-Root-Version";
    static final String ATTR_KOMPATIBEL_AB = "Plaintext-Root-Kompatibel-Ab";

    static final String JAR_LAUNCHER = "org.springframework.boot.loader.launch.JarLauncher";
    static final String PROPERTIES_LAUNCHER = "org.springframework.boot.loader.launch.PropertiesLauncher";

    /** Version der Huelle und aelteste root-Version, gegen die ein Modul gebaut sein darf. */
    record Huelle(String version, String kompatibelAb) {
    }

    private final Path verzeichnis;
    private final ModulQuelle quelle;
    private final String notfallEnv;
    private final Huelle huelle;

    ModulLader(Path verzeichnis, ModulQuelle quelle, String notfallEnv, Huelle huelle) {
        this.verzeichnis = verzeichnis;
        this.quelle = quelle;
        this.notfallEnv = notfallEnv;
        this.huelle = huelle;
    }

    public static void main(String[] args) throws Throwable {
        String dir = System.getenv(VERZEICHNIS_ENV);
        Path verzeichnis = Path.of(dir == null || dir.isBlank() ? STANDARD_VERZEICHNIS : dir);
        List<Path> jars;
        try {
            jars = new ModulLader(verzeichnis, new ModulQuelle(), System.getenv(NOTFALL_ENV), huelleAusManifest())
                    .vorbereiten();
        } catch (Exception ex) {
            System.err.println("[modul-lader] START ABGEBROCHEN: " + ex.getMessage());
            System.err.println("[modul-lader] Notfall: Datei " + verzeichnis.resolve(NOTFALL_DATEI)
                    + " anlegen (oder " + NOTFALL_ENV + "=aus) und neu starten - die Huelle startet dann ohne Module.");
            System.exit(1);
            return;
        }
        starten(jars, args);
    }

    /** Liefert die Jars fuer {@code loader.path}; leer = Start ohne Module. */
    List<Path> vorbereiten() throws IOException, InterruptedException {
        if ("aus".equalsIgnoreCase(notfallEnv == null ? null : notfallEnv.strip())
                || Files.exists(verzeichnis.resolve(NOTFALL_DATEI))) {
            log("Notfall-Schalter aktiv: Start ohne Module (" + NOTFALL_ENV + "=aus bzw. "
                    + verzeichnis.resolve(NOTFALL_DATEI) + ")");
            return List.of();
        }
        Path konfig = verzeichnis.resolve(KONFIG);
        if (!Files.isRegularFile(konfig)) {
            log("keine " + konfig + ": Start ohne Module");
            return List.of();
        }
        List<ModulKonfig.Eintrag> soll = ModulKonfig.lesen(konfig);
        if (soll.isEmpty()) {
            log(konfig + " ohne Module: Start ohne Module");
            return List.of();
        }
        kompatibilitaetPruefen(soll);

        Path cache = verzeichnis.resolve("cache");
        Files.createDirectories(cache);
        List<Path> jars = new ArrayList<>();
        for (ModulKonfig.Eintrag e : soll) {
            jars.add(bereitstellen(e, cache));
        }
        aufraeumen(cache, soll);
        log(jars.size() + " Module bereit: "
                + soll.stream().map(e -> e.name() + ":" + e.version()).collect(Collectors.joining(", ")));
        return jars;
    }

    private void kompatibilitaetPruefen(List<ModulKonfig.Eintrag> soll) {
        if (huelle == null || !ModulKonfig.istRootVersion(huelle.version())
                || !ModulKonfig.istRootVersion(huelle.kompatibelAb())) {
            throw new IllegalStateException("Huellen-Version unbekannt (Manifest-Attribute " + ATTR_VERSION + "/"
                    + ATTR_KOMPATIBEL_AB + " fehlen): ohne sie werden keine Module geladen");
        }
        for (ModulKonfig.Eintrag e : soll) {
            if (ModulKonfig.vergleiche(e.rootMin(), huelle.version()) > 0) {
                throw new IllegalStateException("Modul '" + e.name() + "' braucht root >= " + e.rootMin()
                        + ", die Huelle ist " + huelle.version());
            }
            if (ModulKonfig.vergleiche(e.rootMin(), huelle.kompatibelAb()) < 0) {
                throw new IllegalStateException("Modul '" + e.name() + "' ist gegen root " + e.rootMin()
                        + " gebaut, die Huelle ist erst ab " + huelle.kompatibelAb() + " kompatibel");
            }
        }
    }

    private Path bereitstellen(ModulKonfig.Eintrag e, Path cache) throws IOException, InterruptedException {
        Path ziel = cache.resolve(e.dateiname());
        if (Files.isRegularFile(ziel)) {
            String ist = ModulQuelle.sha256(ziel);
            if (ist.equals(e.sha256())) {
                log("Cache: " + e.dateiname());
                return ziel;
            }
            log("Cache-Datei " + e.dateiname() + " hat sha256 " + ist + " statt " + e.sha256() + ": verworfen, neu holen");
            Files.delete(ziel);
        }
        Path temp = cache.resolve("." + e.dateiname() + "." + UUID.randomUUID() + ".tmp");
        try {
            log("Download: " + quelle.adresse(e));
            String ist = quelle.laden(e, temp);
            if (!ist.equals(e.sha256())) {
                throw new IllegalStateException("Modul '" + e.name() + "' " + e.version() + ": sha256 " + ist
                        + " passt nicht zu " + e.sha256() + " - nicht geladen");
            }
            Files.move(temp, ziel, StandardCopyOption.ATOMIC_MOVE);
            return ziel;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Loescht Jars, die weder im aktuellen noch im vorherigen Stand stehen (Rollback bleibt moeglich). */
    private void aufraeumen(Path cache, List<ModulKonfig.Eintrag> soll) throws IOException {
        Set<String> behalten = new HashSet<>();
        soll.forEach(e -> behalten.add(e.dateiname()));
        Path vorher = verzeichnis.resolve(KONFIG_VORHER);
        if (Files.isRegularFile(vorher)) {
            try {
                ModulKonfig.lesen(vorher).forEach(e -> behalten.add(e.dateiname()));
            } catch (IllegalArgumentException ex) {
                log(vorher + " unlesbar (" + ex.getMessage() + "): Cache wird nicht aufgeraeumt");
                return;
            }
        }
        try (DirectoryStream<Path> dateien = Files.newDirectoryStream(cache)) {
            for (Path p : dateien) {
                String n = p.getFileName().toString();
                if (Files.isRegularFile(p) && (n.endsWith(".jar") && !behalten.contains(n) || n.endsWith(".tmp"))) {
                    log("Cache aufraeumen: " + n);
                    Files.delete(p);
                }
            }
        }
    }

    static Huelle huelleAusManifest() throws IOException {
        Enumeration<URL> manifeste = ClassLoader.getSystemClassLoader().getResources("META-INF/MANIFEST.MF");
        while (manifeste.hasMoreElements()) {
            try (InputStream rein = manifeste.nextElement().openStream()) {
                Attributes a = new Manifest(rein).getMainAttributes();
                String ab = a.getValue(ATTR_KOMPATIBEL_AB);
                if (ab != null && a.getValue("Start-Class") != null) {
                    String v = a.getValue(ATTR_VERSION);
                    return new Huelle(v == null ? null : v.replace("-SNAPSHOT", ""), ab);
                }
            }
        }
        return null;
    }

    private static void starten(List<Path> jars, String[] args) throws Throwable {
        String launcher = JAR_LAUNCHER;
        if (!jars.isEmpty()) {
            System.setProperty("loader.path",
                    jars.stream().map(p -> p.toAbsolutePath().toString()).collect(Collectors.joining(",")));
            launcher = PROPERTIES_LAUNCHER;
        }
        try {
            Class.forName(launcher).getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }

    private static void log(String text) {
        System.out.println("[modul-lader] " + text);
    }
}

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

    public static final String VERZEICHNIS_ENV = "PLAINTEXT_MODULE_VERZEICHNIS";
    public static final String STANDARD_VERZEICHNIS = "/data/module";
    static final String NOTFALL_ENV = "PLAINTEXT_MODULE";
    public static final String NOTFALL_DATEI = "NOTFALL-AUS";
    public static final String KONFIG = "module.yaml";
    public static final String KONFIG_VORHER = "module.yaml.vorher";

    /** Manifest-Attribute des Huellen-Exec-Jars (gesetzt im pom von plaintext-root-webapp). */
    static final String ATTR_VERSION = "Plaintext-Root-Version";
    static final String ATTR_KOMPATIBEL_AB = "Plaintext-Root-Kompatibel-Ab";

    static final String JAR_LAUNCHER = "org.springframework.boot.loader.launch.JarLauncher";
    static final String PROPERTIES_LAUNCHER = "org.springframework.boot.loader.launch.PropertiesLauncher";

    /**
     * Version der Huelle, aelteste root-Version, gegen die ein Modul gebaut sein darf, und das Exec-Jar
     * (fuer die Klassen-Duplikat-Pruefung, Karte 1514).
     */
    public record Huelle(String version, String kompatibelAb, Path archiv) {
    }

    private final Path verzeichnis;
    private final ModulQuelle quelle;
    private final String notfallEnv;
    private final Huelle huelle;
    private final Path konfigVerzeichnis;
    private final Path eingebettet;

    ModulLader(Path verzeichnis, ModulQuelle quelle, String notfallEnv, Huelle huelle) {
        this(verzeichnis, quelle, notfallEnv, huelle, verzeichnis.resolve("kein-konfig-verzeichnis"), null);
    }

    /** Karte 1608: mit Instanz-Konfig ({@code module-quellen.txt}) und Verzeichnis eingebetteter Module. */
    ModulLader(Path verzeichnis, ModulQuelle quelle, String notfallEnv, Huelle huelle, Path konfigVerzeichnis,
               Path eingebettet) {
        this.verzeichnis = verzeichnis;
        this.quelle = quelle;
        this.notfallEnv = notfallEnv;
        this.huelle = huelle;
        this.konfigVerzeichnis = konfigVerzeichnis;
        this.eingebettet = eingebettet;
    }

    public static void main(String[] args) throws Throwable {
        if (args.length > 0 && "--einbetten".equals(args[0])) {
            if (args.length != 4) {
                System.err.println("Aufruf: java -cp app.jar:lader.jar " + ModulLader.class.getName()
                        + " --einbetten <auswahl.yaml> <module-quellen.txt> <zielverzeichnis>");
                System.exit(2);
            }
            try {
                einbetten(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), new ModulQuelle(), huelleAusManifest());
            } catch (Exception ex) {
                System.err.println("[modul-lader] EINBETTEN ABGEBROCHEN: " + ex.getMessage());
                System.exit(1);
            }
            return;
        }
        String dir = System.getenv(VERZEICHNIS_ENV);
        Path verzeichnis = Path.of(dir == null || dir.isBlank() ? STANDARD_VERZEICHNIS : dir);
        String konfig = System.getenv(ModulAuswahl.KONFIG_ENV);
        String eingebettet = System.getenv(ModulAuswahl.EINGEBETTET_ENV);
        List<Path> jars;
        try {
            jars = new ModulLader(verzeichnis, new ModulQuelle(), System.getenv(NOTFALL_ENV), huelleAusManifest(),
                    Path.of(konfig == null || konfig.isBlank() ? ModulAuswahl.STANDARD_KONFIG : konfig),
                    Path.of(eingebettet == null || eingebettet.isBlank() ? ModulAuswahl.STANDARD_EINGEBETTET : eingebettet))
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

    /**
     * Karte 1515: prueft eine Konfig mit genau dem Parser, den der Lader beim Start nimmt (Format, Muster,
     * Stueckliste). Fuer die Modul-Ansicht, BEVOR sie die Datei im PV schreibt.
     *
     * @return {@code name:version} je Eintrag, Module und Bibliotheken
     * @throws IllegalArgumentException mit der Meldung des Laders, wenn er die Konfig ablehnen wuerde
     */
    public static List<String> konfigPruefen(String text) {
        return ModulKonfig.parsen(text, KONFIG).stream()
                .map(e -> e.name() + ":" + e.version() + (e.bibliothek() ? " (Bibliothek)" : ""))
                .toList();
    }

    /** Liefert die Jars fuer {@code loader.path}; leer = Start ohne Module. */
    List<Path> vorbereiten() throws IOException, InterruptedException {
        if ("aus".equalsIgnoreCase(notfallEnv == null ? null : notfallEnv.strip())
                || Files.exists(verzeichnis.resolve(NOTFALL_DATEI))) {
            log("Notfall-Schalter aktiv: Start ohne Module (" + NOTFALL_ENV + "=aus bzw. "
                    + verzeichnis.resolve(NOTFALL_DATEI) + ")");
            return List.of();
        }
        Path auswahl = verzeichnis.resolve(ModulAuswahl.AUSWAHL);
        boolean hatEingebettet = eingebettet != null && Files.isRegularFile(eingebettet.resolve(ModulAuswahl.EINGEBETTET_INDEX));
        if (hatEingebettet || Files.isRegularFile(auswahl)) {
            ModulAuswahl.Quellen q = ModulAuswahl.quellenLesen(konfigVerzeichnis.resolve(ModulAuswahl.QUELLEN));
            if (hatEingebettet && !(q.eingebettetUeberschreibbar() && Files.isRegularFile(auswahl))) {
                if (Files.isRegularFile(auswahl)) {
                    log("Laufzeit-Auswahl " + auswahl + " ignoriert: eingebettete Module haben Vorrang (in "
                            + ModulAuswahl.QUELLEN + " 'eingebettet ueberschreibbar' setzen, um das zu aendern)");
                }
                return eingebettetLaden(q);
            }
            if (hatEingebettet) {
                log("eingebettete Module uebersteuert: Laufzeit-Auswahl gilt ('eingebettet ueberschreibbar')");
            }
            return auswahlLaden(q, auswahl);
        }
        return konfigLaden();
    }

    /** Der {@code module.yaml}-Weg (ohne Auswahl, und Karte 1618 als Rueckfall beim ersten Start). */
    private List<Path> konfigLaden() throws IOException, InterruptedException {
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
        ModulPruefung.pruefen(soll, jars, huelle.archiv());
        aufraeumen(cache, soll);
        log(jars.size() + " Module bereit: "
                + soll.stream().map(e -> e.name() + ":" + e.version()).collect(Collectors.joining(", ")));
        return jars;
    }

    /** Karte 1608: eingebettete Module (festes Image); jeder Fehler bricht den Start ab. */
    private List<Path> eingebettetLaden(ModulAuswahl.Quellen q) throws IOException, InterruptedException {
        Path index = eingebettet.resolve(ModulAuswahl.EINGEBETTET_INDEX);
        List<ModulAuswahl.Zeile> zeilen = ModulAuswahl.indexParsen(Files.readString(index), index.toString(), index.toUri());
        List<Path> jars = pruefenUndBereitstellen(zeilen, q, eingebettet, false);
        log("eingebettete Module (" + eingebettet + "): " + jars.size() + " Module bereit: " + namen(zeilen));
        return jars;
    }

    /**
     * Karte 1608: Auswahl aus den Registries; scheitert das, gilt der letzte gute Stand ({@code stand.txt}),
     * erneut gegen Signatur und sha256 geprueft. Ist auch der ungueltig: Start ohne Module.
     */
    private List<Path> auswahlLaden(ModulAuswahl.Quellen q, Path auswahl) throws IOException, InterruptedException {
        Path cache = verzeichnis.resolve("cache");
        Path stand = verzeichnis.resolve(ModulAuswahl.STAND);
        java.util.Map<String, String> ausgerollt = ausgerollt();
        try {
            List<ModulAuswahl.Wahl> wahl = mitAusgerollterVersion(
                    ModulAuswahl.auswahlParsen(Files.readString(auswahl), auswahl.toString()), ausgerollt);
            huellePruefen();
            ModulAuswahl.Angebot angebot = wahl.isEmpty() ? new ModulAuswahl.Angebot(List.of(), java.util.Map.of())
                    : ModulAuswahl.angebot(q, quelle);
            List<ModulAuswahl.Zeile> zeilen = ModulAuswahl.aufloesen(wahl, angebot, q.schluessel(), huelle, true);
            Files.createDirectories(cache);
            List<Path> jars = pruefenUndBereitstellen(zeilen, q, cache, true);
            ModulAuswahl.atomarSchreiben(stand, ModulAuswahl.indexText(zeilen, "letzter guter Stand, geschrieben vom ModulLader"));
            Set<String> behalten = new HashSet<>();
            zeilen.forEach(z -> behalten.add(z.dateiname()));
            cacheAufraeumen(cache, behalten);
            ModulAuswahl.statusSchreiben(verzeichnis, "OK", angebot.fehler().isEmpty() ? null
                    : "Registry-Fehler (Auswahl trotzdem aufgeloest): " + angebot.fehler(), zeilen);
            log("Auswahl: " + jars.size() + " Module bereit: " + namen(zeilen));
            return jars;
        } catch (InterruptedException ex) {
            throw ex;
        } catch (Exception ex) {
            String grund = ex.getMessage();
            log("AUSWAHL NICHT ANWENDBAR: " + grund + " - starte mit dem letzten guten Stand");
            try {
                java.util.Optional<String> text = ModulAuswahl.lesen(stand);
                if (text.isPresent() && !ausgerollt.isEmpty()) {
                    String abweichung = abweichung(ModulAuswahl.indexParsen(text.get(), stand.toString(), stand.toUri()), ausgerollt);
                    if (abweichung != null) {
                        // Karte 1618: der Stand stammt aus einem frueheren Deploy dieses Slots; alte Module zum neuen
                        // App-Jar waeren schlimmer als der module.yaml-Weg mit genau den ausgerollten Modulen.
                        log("letzter guter Stand passt nicht zum ausgerollten App-Jar (" + abweichung + ")");
                        text = java.util.Optional.empty();
                    }
                }
                if (text.isEmpty()) {
                    if (Files.isRegularFile(verzeichnis.resolve(KONFIG))) {
                        // Karte 1618 (Entscheid Daniel): erster Start mit Auswahl, noch kein letzter guter Stand ->
                        // die Module wie vor der Umstellung aus module.yaml statt einer Instanz ohne Fachseiten.
                        log("kein letzter guter Stand (" + stand + "): Rueckfall auf " + KONFIG);
                        List<Path> jars = konfigLaden();
                        ModulAuswahl.statusSchreiben(verzeichnis, "RUECKFALL-MODULE-YAML", grund, List.of());
                        return jars;
                    }
                    log("kein letzter guter Stand (" + stand + "), keine " + KONFIG + ": Start ohne Module");
                    ModulAuswahl.statusSchreiben(verzeichnis, "OHNE-MODULE", grund, List.of());
                    return List.of();
                }
                List<ModulAuswahl.Zeile> zeilen = ModulAuswahl.indexParsen(text.get(), stand.toString(), stand.toUri());
                List<Path> jars = pruefenUndBereitstellen(zeilen, q, cache, false);
                ModulAuswahl.statusSchreiben(verzeichnis, "LETZTER-GUTER-STAND", grund, zeilen);
                log("letzter guter Stand: " + jars.size() + " Module bereit: " + namen(zeilen));
                return jars;
            } catch (InterruptedException ex2) {
                throw ex2;
            } catch (Exception ex2) {
                log("letzter guter Stand ungueltig (" + ex2.getMessage() + "): Start ohne Module");
                ModulAuswahl.statusSchreiben(verzeichnis, "OHNE-MODULE",
                        grund + "; letzter guter Stand ungueltig: " + ex2.getMessage(), List.of());
                return List.of();
            }
        }
    }

    /**
     * Karte 1618: Module aus {@code module.yaml} (schreibt {@code huelle/start.sh} bei jedem Start aus dem ausgerollten
     * App-Jar) als name -&gt; Version; leer ohne Datei oder wenn sie unlesbar ist.
     */
    private java.util.Map<String, String> ausgerollt() {
        Path konfig = verzeichnis.resolve(KONFIG);
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        if (Files.isRegularFile(konfig)) {
            try {
                ModulKonfig.lesen(konfig).stream().filter(e -> !e.bibliothek()).forEach(e -> m.put(e.name(), e.version()));
            } catch (Exception ex) {
                log(konfig + " unlesbar (" + ex.getMessage() + "): Auswahl ohne ausgerollte Versionen");
                return java.util.Map.of();
            }
        }
        return m;
    }

    /**
     * Karte 1618: {@code latest} fuer ein Modul des ausgerollten App-Jars heisst dessen Version. So bringt ein Deploy
     * die neuen Module (wie bisher), ein Neustart des alten Slots (Rueckweg) zieht keine neueren nach, und ein Start vor
     * dem Hochladen in die Registry nimmt keine aelteren.
     */
    private static List<ModulAuswahl.Wahl> mitAusgerollterVersion(List<ModulAuswahl.Wahl> wahl,
                                                                   java.util.Map<String, String> ausgerollt) {
        List<ModulAuswahl.Wahl> out = new ArrayList<>();
        for (ModulAuswahl.Wahl w : wahl) {
            String v = ausgerollt.get(w.name());
            if (ModulAuswahl.LATEST.equals(w.version()) && v != null) {
                log("Auswahl " + w.name() + ": latest = ausgerollte Version " + v + " (" + KONFIG + ")");
                out.add(new ModulAuswahl.Wahl(w.name(), v));
            } else {
                out.add(w);
            }
        }
        return out;
    }

    /** null, wenn jedes Modul des Stands, das auch ausgerollt ist, dieselbe Version hat; sonst das erste Beispiel. */
    private static String abweichung(List<ModulAuswahl.Zeile> stand, java.util.Map<String, String> ausgerollt) {
        for (ModulAuswahl.Zeile z : stand) {
            String v = ausgerollt.get(z.name());
            if (!z.istBibliothek() && v != null && !v.equals(z.version())) {
                return z.name() + " " + z.version() + " statt " + v;
            }
        }
        return null;
    }

    /** Signatur, Jar (sha256), Kompatibilitaet und Inhaltspruefung; erst danach ist ein Jar ladbar. */
    private List<Path> pruefenUndBereitstellen(List<ModulAuswahl.Zeile> zeilen, ModulAuswahl.Quellen q, Path ort,
                                               boolean herunterladen) throws IOException, InterruptedException {
        huellePruefen();
        zeilen.forEach(z -> ModulAuswahl.signaturPruefen(z, q.schluessel()));
        List<ModulAuswahl.Zeile> module = zeilen.stream().filter(z -> !z.istBibliothek()).toList();
        List<ModulAuswahl.Zeile> alle = new ArrayList<>(module);
        alle.addAll(ModulAuswahl.bibliotheken(module, zeilen)); // sha256 der Bibliotheken nur aus der Modul-Signatur
        alle = ModulAuswahl.gegenHuelle(alle, huelle.archiv());
        List<ModulKonfig.Eintrag> eintraege = ModulAuswahl.eintraege(alle);
        kompatibilitaetPruefen(eintraege);
        List<Path> jars = new ArrayList<>();
        for (ModulAuswahl.Zeile z : alle) {
            jars.add(ModulAuswahl.bereitstellen(z, ort, quelle, herunterladen));
        }
        ModulPruefung.pruefen(eintraege, jars, huelle.archiv());
        return jars;
    }

    /**
     * Karte 1608, festes Image: loest die Auswahl (nur feste Versionen) zur Bauzeit auf, laedt und prueft wie beim
     * Start und schreibt {@code <ziel>/index.txt} (signierte Zeilen) samt Jars.
     */
    static List<ModulAuswahl.Zeile> einbetten(Path auswahl, Path quellen, Path ziel, ModulQuelle quelle, Huelle huelle)
            throws IOException, InterruptedException {
        ModulAuswahl.Quellen q = ModulAuswahl.quellenLesen(quellen);
        if (!Files.isRegularFile(quellen)) {
            throw new IllegalArgumentException(quellen + " fehlt");
        }
        ModulLader lader = new ModulLader(ziel, quelle, null, huelle, quellen.getParent(), null);
        lader.huellePruefen();
        List<ModulAuswahl.Wahl> wahl = ModulAuswahl.auswahlParsen(Files.readString(auswahl), auswahl.toString());
        ModulAuswahl.Angebot angebot = ModulAuswahl.angebot(q, quelle);
        List<ModulAuswahl.Zeile> zeilen = ModulAuswahl.aufloesen(wahl, angebot, q.schluessel(), huelle, false);
        Files.createDirectories(ziel);
        lader.pruefenUndBereitstellen(zeilen, q, ziel, true);
        ModulAuswahl.atomarSchreiben(ziel.resolve(ModulAuswahl.EINGEBETTET_INDEX),
                ModulAuswahl.indexText(zeilen, "eingebettet zur Bauzeit aus " + auswahl.getFileName()));
        log("eingebettet nach " + ziel + ": " + namen(zeilen));
        return zeilen;
    }

    private static String namen(List<ModulAuswahl.Zeile> zeilen) {
        return zeilen.stream().map(z -> z.name() + ":" + z.version()).collect(Collectors.joining(", "));
    }

    private static void cacheAufraeumen(Path cache, Set<String> behalten) throws IOException {
        try (DirectoryStream<Path> dateien = Files.newDirectoryStream(cache)) {
            for (Path p : dateien) {
                String n = p.getFileName().toString();
                if (Files.isRegularFile(p) && (n.endsWith(".jar") && !behalten.contains(n) || n.endsWith(".tmp"))) {
                    log("Cache aufraeumen (deaktiviert): " + n);
                    Files.delete(p);
                }
            }
        }
    }

    private void huellePruefen() {
        if (huelle == null || !ModulKonfig.istRootVersion(huelle.version())
                || !ModulKonfig.istRootVersion(huelle.kompatibelAb())) {
            throw new IllegalStateException("Huellen-Version unbekannt (Manifest-Attribute " + ATTR_VERSION + "/"
                    + ATTR_KOMPATIBEL_AB + " fehlen): ohne sie werden keine Module geladen");
        }
    }

    private void kompatibilitaetPruefen(List<ModulKonfig.Eintrag> soll) {
        huellePruefen();
        for (ModulKonfig.Eintrag e : soll) {
            if (e.bibliothek()) {
                continue;
            }
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

    public static Huelle huelleAusManifest() throws IOException {
        Enumeration<URL> manifeste = ClassLoader.getSystemClassLoader().getResources("META-INF/MANIFEST.MF");
        while (manifeste.hasMoreElements()) {
            URL url = manifeste.nextElement();
            try (InputStream rein = url.openStream()) {
                Attributes a = new Manifest(rein).getMainAttributes();
                String ab = a.getValue(ATTR_KOMPATIBEL_AB);
                if (ab != null && a.getValue("Start-Class") != null) {
                    String v = a.getValue(ATTR_VERSION);
                    return new Huelle(v == null ? null : v.replace("-SNAPSHOT", ""), ab, archivVon(url));
                }
            }
        }
        return null;
    }

    /** {@code jar:file:/app/app.jar!/META-INF/MANIFEST.MF} -> {@code /app/app.jar}; sonst {@code null}. */
    static Path archivVon(URL manifest) {
        String s = manifest.toString();
        int bang = s.indexOf("!/");
        if (!s.startsWith("jar:file:") || bang < 0) {
            return null;
        }
        return Path.of(java.net.URI.create(s.substring(4, bang)));
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

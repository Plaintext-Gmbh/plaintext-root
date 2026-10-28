/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.lader;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Karte 1608: Module per Auswahl aus signierten Registries (Ed25519).
 *
 * <ul>
 *   <li>{@code module/auswahl.yaml} (geschrieben von der Seite, nur ROOT): Name und Version oder {@code latest}.
 *       Was nicht drinsteht, ist deaktiviert.</li>
 *   <li>{@code config/module-quellen.txt} (Instanz-Konfig): {@code schluessel ed25519 <base64 X.509>},
 *       {@code registry <name> <url>}, optional {@code eingebettet ueberschreibbar}.</li>
 *   <li>Registry = Textdatei {@value #INDEX_KOPF} mit einer Zeile je Jar:
 *       {@code <modul|api> <name> <version> root-min=.. sha256=.. [braucht=a:1,b:2] datei=<relativ> signatur=<base64>}.
 *       Signiert ist {@link Zeile#nachricht()}: Inhalt (sha256), Name, Version, root-min, braucht. Nicht der Ort.</li>
 * </ul>
 * Ohne gueltige Signatur eines konfigurierten Schluessels wird kein Jar geladen, egal aus welcher Registry.
 */
public final class ModulAuswahl {

    public static final String AUSWAHL = "auswahl.yaml";
    public static final String AUSWAHL_VORHER = "auswahl.yaml.vorher";
    public static final String STAND = "stand.txt";
    public static final String STATUS = "status.txt";
    public static final String QUELLEN = "module-quellen.txt";
    public static final String KONFIG_ENV = "PLAINTEXT_KONFIG_VERZEICHNIS";
    public static final String STANDARD_KONFIG = "/data/config";
    public static final String EINGEBETTET_ENV = "PLAINTEXT_MODULE_EINGEBETTET";
    public static final String STANDARD_EINGEBETTET = "/huelle/module-eingebettet";
    public static final String EINGEBETTET_INDEX = "index.txt";
    public static final String LATEST = "latest";
    static final String INDEX_KOPF = "plaintext-modul-index 1";
    static final String SIGNATUR_KOPF = "plaintext-modul-signatur-1";
    /** Karte 1610: Signatur-Wert einer unsignierten Zeile (nur Entwicklungsmodus, siehe {@link #entwicklungsmodusVerweigert}). */
    public static final String UNSIGNIERT = "UNSIGNIERT";
    static final String UNSIGNIERT_ENV = "PLAINTEXT_MODULE_UNSIGNIERT";
    static final String UNSIGNIERT_WERT = "entwicklung";
    /** System-Property, die der Lader setzt, wenn er unsignierte Module geladen hat (name:version,...). */
    public static final String UNSIGNIERT_GELADEN = "plaintext.module.unsigniert-geladen";

    private static final Set<String> ZEILEN_SCHLUESSEL = Set.of("root-min", "sha256", "braucht", "bibliotheken", "datei", "signatur");
    private static final Pattern BIBLIOTHEK = Pattern.compile("([a-z][a-z0-9_-]*(?:\\.[a-z0-9_-]+)*):([A-Za-z0-9][A-Za-z0-9_.-]*):([0-9][A-Za-z0-9_.-]*)@([0-9a-f]{64})");
    private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/=]+");

    /** Ein Eintrag der Auswahl; {@code version} ist eine feste Version oder {@link #LATEST}. */
    public record Wahl(String name, String version) {
    }

    /**
     * Eine Zeile eines Registry-Index. {@code registry}/{@code index} sagen, woher sie kam.
     * Art {@code bib}: Ort einer Fremd-Bibliothek ({@code name} = gruppe:artifact), ohne eigene Signatur; ihr sha256
     * steht in der signierten Liste {@code bibliotheken} des Moduls ({@code gruppe:artifact:version@sha256}).
     */
    public record Zeile(String art, String name, String version, String rootMin, String sha256, List<String> braucht,
                        List<String> bibliotheken, String datei, String signatur, String registry, URI index) {

        /** Der signierte Text (Inhalt, Name, Version, root-min, braucht und die Bibliotheken samt sha256). */
        public String nachricht() {
            return SIGNATUR_KOPF + "\n" + art + "\n" + name + "\n" + version + "\n" + rootMin + "\n" + sha256 + "\n"
                    + String.join(",", braucht) + "\n" + String.join(",", bibliotheken) + "\n";
        }

        public boolean istBibliothek() {
            return "bib".equals(art);
        }

        public boolean istModul() {
            return "modul".equals(art);
        }

        /** Zeile im Indexformat (fuer stand.txt und den eingebetteten Index). */
        public String alsIndexZeile() {
            if (istBibliothek()) {
                return "bib " + name + " " + version + " datei=" + datei;
            }
            return art + " " + name + " " + version + " root-min=" + rootMin + " sha256=" + sha256
                    + (braucht.isEmpty() ? "" : " braucht=" + String.join(",", braucht))
                    + (bibliotheken.isEmpty() ? "" : " bibliotheken=" + String.join(",", bibliotheken))
                    + " datei=" + datei + " signatur=" + signatur;
        }

        /** Artefaktname ohne Gruppe. */
        String artefakt() {
            return istBibliothek() ? name.substring(name.indexOf(':') + 1) : name;
        }

        String dateiname() {
            return artefakt() + "-" + version + ".jar";
        }

        /** Als Eintrag fuer die bestehenden Pruefungen des Laders (Stueckliste = braucht + Bibliotheken). */
        ModulKonfig.Eintrag eintrag(boolean bibliothek) {
            if (istBibliothek()) {
                return new ModulKonfig.Eintrag(name.substring(0, name.indexOf(':')), artefakt(), version, sha256, null,
                        List.of(), true);
            }
            List<String> stueck = new ArrayList<>(braucht.stream().map(b -> ModulKonfig.STANDARD_GRUPPE + ":" + b).toList());
            bibliotheken.forEach(b -> stueck.add(b.substring(0, b.indexOf('@'))));
            return new ModulKonfig.Eintrag(ModulKonfig.STANDARD_GRUPPE, name, version, sha256, rootMin,
                    List.copyOf(stueck), bibliothek);
        }
    }

    /**
     * Karte 1618: {@code anmeldungEnv} = Name einer Umgebungsvariable mit {@code benutzer:passwort} (z.B. der Token einer
     * Nextcloud-Freigabe); dann geht HTTP Basic an diese Registry. Der Wert steht nie in der Datei, der URL oder im Log.
     */
    public record Registry(String name, URI url, String anmeldungEnv) {
        public Registry(String name, URI url) {
            this(name, url, null);
        }
    }

    static final String ANMELDUNG_PRAEFIX = "anmeldung-env=";
    static final java.util.regex.Pattern ANMELDUNG = java.util.regex.Pattern.compile("anmeldung-env=[A-Z][A-Z0-9_]{0,63}");

    public record Quellen(List<PublicKey> schluessel, List<Registry> registries, boolean eingebettetUeberschreibbar) {
    }

    /** Was alle Registries anbieten, mit Fehler je Registry (fuer Lader und Seite). */
    public record Angebot(List<Zeile> zeilen, Map<String, String> fehler) {
    }

    private ModulAuswahl() {
    }

    // ------------------------------------------------------------------------------------------ Dateien lesen

    /** Liest {@code module-quellen.txt}; fehlt die Datei, gibt es weder Schluessel noch Registries. */
    public static Quellen quellenLesen(Path datei) throws IOException {
        if (!Files.isRegularFile(datei)) {
            return new Quellen(List.of(), List.of(), false);
        }
        List<PublicKey> schluessel = new ArrayList<>();
        List<Registry> registries = new ArrayList<>();
        boolean ueberschreibbar = false;
        int nr = 0;
        for (String roh : Files.readAllLines(datei, StandardCharsets.UTF_8)) {
            nr++;
            String z = ohneKommentar(roh).strip();
            if (z.isEmpty()) {
                continue;
            }
            String[] t = z.split("\\s+");
            if (t.length == 3 && t[0].equals("schluessel") && t[1].equals("ed25519")) {
                schluessel.add(schluessel(t[2], datei + ":" + nr));
            } else if ((t.length == 3 || t.length == 4 && ANMELDUNG.matcher(t[3]).matches())
                    && t[0].equals("registry") && ModulKonfig.NAME.matcher(t[1]).matches()) {
                URI u = URI.create(t[2]);
                String anmeldung = t.length == 4 ? t[3].substring(ANMELDUNG_PRAEFIX.length()) : null;
                if (anmeldung != null && !"https".equals(u.getScheme())) {
                    throw new IllegalArgumentException(datei + ":" + nr + ": Anmeldung nur mit einer https-Adresse");
                }
                boolean netz = ("https".equals(u.getScheme()) || "http".equals(u.getScheme())) && u.getHost() != null;
                if (!netz && !"file".equals(u.getScheme())) {
                    throw new IllegalArgumentException(datei + ":" + nr + ": Registry braucht eine http(s)- oder file-Adresse");
                }
                if (registries.stream().anyMatch(r -> r.name().equals(t[1]))) {
                    throw new IllegalArgumentException(datei + ":" + nr + ": Registry '" + t[1] + "' doppelt");
                }
                registries.add(new Registry(t[1], u, anmeldung));
            } else if (t.length == 2 && t[0].equals("eingebettet") && t[1].equals("ueberschreibbar")) {
                ueberschreibbar = true;
            } else {
                throw new IllegalArgumentException(datei + ":" + nr + ": unbekannte Zeile (erlaubt: 'schluessel ed25519 <base64>', "
                        + "'registry <name> <url> [anmeldung-env=<VARIABLE>]', 'eingebettet ueberschreibbar')");
            }
        }
        return new Quellen(List.copyOf(schluessel), List.copyOf(registries), ueberschreibbar);
    }

    static PublicKey schluessel(String base64, String wo) {
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalArgumentException(wo + ": kein Ed25519-Schluessel (X.509, Base64): " + ex.getMessage());
        }
    }

    /** Parser fuer {@code auswahl.yaml}; gleiche enge YAML-Teilmenge wie {@link ModulKonfig}. */
    public static List<Wahl> auswahlParsen(String text, String quelle) {
        List<Wahl> out = new ArrayList<>();
        Map<String, String> aktuell = null;
        List<Map<String, String>> roh = new ArrayList<>();
        boolean kopf = false;
        int nr = 0;
        for (String zeile : text.split("\r?\n", -1)) {
            nr++;
            String z = ohneKommentar(zeile);
            if (z.isBlank()) {
                continue;
            }
            if (!kopf) {
                if (z.equals("auswahl: []")) {
                    return List.of();
                }
                if (!z.equals("auswahl:")) {
                    throw new IllegalArgumentException(quelle + ":" + nr + ": erwartet 'auswahl:' als erste Zeile");
                }
                kopf = true;
                continue;
            }
            String rest;
            if (z.startsWith("  - ")) {
                aktuell = new LinkedHashMap<>();
                roh.add(aktuell);
                rest = z.substring(4);
            } else if (z.startsWith("    ") && !z.startsWith("     ") && aktuell != null) {
                rest = z.substring(4);
            } else {
                throw new IllegalArgumentException(quelle + ":" + nr + ": Einrueckung passt nicht");
            }
            int dp = rest.indexOf(':');
            String k = dp > 0 ? rest.substring(0, dp).strip() : "";
            if (!(k.equals("name") || k.equals("version")) || aktuell.put(k, rest.substring(dp + 1).strip()) != null) {
                throw new IllegalArgumentException(quelle + ":" + nr + ": erwartet je Eintrag genau 'name' und 'version'");
            }
        }
        if (!kopf) {
            throw new IllegalArgumentException(quelle + ": leer, erwartet 'auswahl:'");
        }
        Set<String> namen = new LinkedHashSet<>();
        for (Map<String, String> m : roh) {
            String n = m.get("name");
            String v = m.get("version");
            if (n == null || !ModulKonfig.NAME.matcher(n).matches()) {
                throw new IllegalArgumentException(quelle + ": ungueltiger Name '" + n + "'");
            }
            if (v == null || !(v.equals(LATEST) || ModulKonfig.VERSION.matcher(v).matches())) {
                throw new IllegalArgumentException(quelle + ": Modul '" + n + "', ungueltige Version '" + v + "'");
            }
            if (!namen.add(n)) {
                throw new IllegalArgumentException(quelle + ": Modul '" + n + "' doppelt");
            }
            out.add(new Wahl(n, v));
        }
        return List.copyOf(out);
    }

    /** Text fuer {@code auswahl.yaml}; wird vor dem Schreiben mit {@link #auswahlParsen} geprueft. */
    public static String auswahlText(List<Wahl> wahl, String kommentar) {
        StringBuilder sb = new StringBuilder("# ").append(kommentar.replaceAll("[\r\n]", " ")).append('\n');
        if (wahl.isEmpty()) {
            return sb.append("auswahl: []\n").toString();
        }
        sb.append("auswahl:\n");
        wahl.forEach(w -> sb.append("  - name: ").append(w.name()).append("\n    version: ").append(w.version()).append('\n'));
        String text = sb.toString();
        auswahlParsen(text, AUSWAHL);
        return text;
    }

    /** Parser fuer einen Index (Registry, {@code stand.txt}, eingebetteter Index). */
    static List<Zeile> indexParsen(String text, String registry, URI index) {
        List<Zeile> out = new ArrayList<>();
        boolean kopf = false;
        int nr = 0;
        for (String roh : text.split("\r?\n", -1)) {
            nr++;
            String z = ohneKommentar(roh).strip();
            if (z.isEmpty()) {
                continue;
            }
            String wo = registry + ":" + nr;
            if (!kopf) {
                if (!z.equals(INDEX_KOPF)) {
                    throw new IllegalArgumentException(wo + ": erwartet '" + INDEX_KOPF + "' als erste Zeile");
                }
                kopf = true;
                continue;
            }
            String[] t = z.split("\\s+");
            if (t.length < 4 || !(t[0].equals("modul") || t[0].equals("api") || t[0].equals("bib"))) {
                throw new IllegalArgumentException(wo + ": erwartet '<modul|api|bib> <name> <version> schluessel=wert ...'");
            }
            Map<String, String> w = new LinkedHashMap<>();
            for (int i = 3; i < t.length; i++) {
                int gl = t[i].indexOf('=');
                String k = gl > 0 ? t[i].substring(0, gl) : t[i];
                if (!ZEILEN_SCHLUESSEL.contains(k) || w.put(k, t[i].substring(gl + 1)) != null) {
                    throw new IllegalArgumentException(wo + ": Schluessel '" + k + "' unbekannt oder doppelt");
                }
            }
            String datei = w.get("datei");
            if (datei == null || datei.isEmpty() || datei.contains("://") || datei.startsWith("/") || datei.contains("\\")) {
                throw new IllegalArgumentException(wo + ": 'datei' muss ein relativer Pfad sein");
            }
            String version = pruefe(t[2], ModulKonfig.VERSION, "Version", wo);
            if (t[0].equals("bib")) {
                if (w.size() != 1 || !BIBLIOTHEK.matcher(t[1] + ":" + version + "@" + "0".repeat(64)).matches()) {
                    throw new IllegalArgumentException(wo + ": erwartet 'bib <gruppe:artifact> <version> datei=<relativ>'");
                }
                out.add(new Zeile("bib", t[1], version, null, null, List.of(), List.of(), datei, null, registry, index));
                continue;
            }
            String name = pruefe(t[1], ModulKonfig.NAME, "Name", wo);
            String rootMin = pruefe(w.get("root-min"), ModulKonfig.ROOT_VERSION, "root-min", wo);
            String sha = pruefe(w.get("sha256"), ModulKonfig.SHA256, "sha256", wo);
            String sig = pruefe(w.get("signatur"), BASE64, "signatur", wo);
            List<String> bibs = new ArrayList<>();
            String bw = w.getOrDefault("bibliotheken", "");
            for (String teil : bw.isEmpty() ? new String[0] : bw.split(",")) {
                if (!BIBLIOTHEK.matcher(teil).matches()) {
                    throw new IllegalArgumentException(wo + ": 'bibliotheken' erwartet gruppe:artifact:version@sha256[,...]");
                }
                bibs.add(teil);
            }
            List<String> braucht = new ArrayList<>();
            String b = w.getOrDefault("braucht", "");
            for (String teil : b.isEmpty() ? new String[0] : b.split(",")) {
                String[] nv = teil.split(":", -1);
                if (nv.length != 2 || !ModulKonfig.NAME.matcher(nv[0]).matches() || !ModulKonfig.VERSION.matcher(nv[1]).matches()) {
                    throw new IllegalArgumentException(wo + ": 'braucht' erwartet name:version[,name:version]");
                }
                braucht.add(teil);
            }
            out.add(new Zeile(t[0], name, version, rootMin, sha, List.copyOf(braucht), List.copyOf(bibs), datei, sig,
                    registry, index));
        }
        if (!kopf) {
            throw new IllegalArgumentException(registry + ": leer, erwartet '" + INDEX_KOPF + "'");
        }
        return List.copyOf(out);
    }

    /** Liest alle Registries; eine nicht erreichbare oder kaputte Registry steht in {@link Angebot#fehler()}. */
    static Angebot angebot(Quellen q, ModulQuelle quelle) {
        return angebot(q, quelle, System.getenv());
    }

    static Angebot angebot(Quellen q, ModulQuelle quelle, Map<String, String> umgebung) {
        List<Zeile> zeilen = new ArrayList<>();
        Map<String, String> fehler = new LinkedHashMap<>();
        for (Registry r : q.registries()) {
            try {
                if (r.anmeldungEnv() != null) {
                    String wert = umgebung.get(r.anmeldungEnv());
                    if (wert == null || !wert.contains(":")) {
                        throw new IllegalStateException("Anmeldung: Umgebungsvariable " + r.anmeldungEnv()
                                + " fehlt oder ohne ':' (erwartet benutzer:passwort)");
                    }
                    quelle.anmeldung(r.url(), wert);
                }
                zeilen.addAll(indexParsen(quelle.text(r.url()), r.name(), r.url()));
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                fehler.put(r.name(), ex.getMessage());
            }
        }
        return new Angebot(List.copyOf(zeilen), fehler);
    }

    /** Fuer die Seite: Angebot aller Registries aus {@code module-quellen.txt} im Konfig-Verzeichnis. */
    public static Angebot angebot(Path konfigVerzeichnis) throws IOException {
        return angebot(quellenLesen(konfigVerzeichnis.resolve(QUELLEN)), new ModulQuelle());
    }

    // ------------------------------------------------------------------------------------------ Signatur

    /** true, wenn einer der Schluessel die Zeile signiert hat. */
    public static boolean signaturGueltig(Zeile z, List<PublicKey> schluessel) {
        byte[] sig;
        try {
            sig = Base64.getDecoder().decode(z.signatur());
        } catch (IllegalArgumentException ex) {
            return false;
        }
        for (PublicKey k : schluessel) {
            try {
                Signature s = Signature.getInstance("Ed25519");
                s.initVerify(k);
                s.update(z.nachricht().getBytes(StandardCharsets.UTF_8));
                if (s.verify(sig)) {
                    return true;
                }
            } catch (GeneralSecurityException ex) {
                // naechster Schluessel; eine kaputte Signatur ist einfach ungueltig
            }
        }
        return false;
    }

    static void signaturPruefen(Zeile z, List<PublicKey> schluessel) {
        signaturPruefen(z, schluessel, System.getenv(), System.getProperty("spring.profiles.active"));
    }

    static void signaturPruefen(Zeile z, List<PublicKey> schluessel, Map<String, String> env, String profilProperty) {
        if (z.istBibliothek()) {
            return; // gedeckt durch die Signatur des Moduls (sha256 in dessen Liste 'bibliotheken')
        }
        if (UNSIGNIERT.equals(z.signatur())) {
            String nein = entwicklungsmodusVerweigert(z, env, profilProperty);
            if (nein != null) {
                throw new IllegalStateException("unsigniertes Modul " + z.name() + " " + z.version() + " aus Registry '"
                        + z.registry() + "' abgelehnt: " + nein);
            }
            System.out.println("[modul-lader] !!! ENTWICKLUNGSMODUS: UNSIGNIERTES Modul " + z.name() + " " + z.version()
                    + " aus " + z.index() + " geladen - nur lokal (./traeger), NIE auf PROD !!!");
            String bisher = System.getProperty(UNSIGNIERT_GELADEN);
            System.setProperty(UNSIGNIERT_GELADEN, (bisher == null ? "" : bisher + ",") + z.name() + ":" + z.version());
            return;
        }
        if (schluessel.isEmpty()) {
            throw new IllegalStateException("kein Signatur-Schluessel konfiguriert (" + QUELLEN
                    + ": 'schluessel ed25519 ...'): ohne ihn wird kein Modul geladen");
        }
        if (!signaturGueltig(z, schluessel)) {
            throw new IllegalStateException("Signatur ungueltig: " + z.name() + " " + z.version() + " aus Registry '"
                    + z.registry() + "' - nicht geladen");
        }
    }

    /**
     * Karte 1610: Eine Zeile mit {@code signatur=UNSIGNIERT} ist nur erlaubt, wenn ALLES gilt: Umgebung
     * {@code PLAINTEXT_MODULE_UNSIGNIERT=entwicklung}, kein Profil {@code prod} (Umgebung und System-Property; die
     * Spring-Seite prueft das Profil nach dem Start noch einmal, siehe {@link #UNSIGNIERT_GELADEN}) und die Zeile
     * kommt aus einer {@code file:}-Registry. Sonst der Grund der Ablehnung.
     */
    static String entwicklungsmodusVerweigert(Zeile z, Map<String, String> env, String profilProperty) {
        if (!UNSIGNIERT_WERT.equals(env.get(UNSIGNIERT_ENV))) {
            return "Entwicklungsmodus aus (" + UNSIGNIERT_ENV + "=" + UNSIGNIERT_WERT + " fehlt)";
        }
        for (String profile : new String[] {env.get("SPRING_PROFILES_ACTIVE"), profilProperty}) {
            for (String p : profile == null ? new String[0] : profile.split(",")) {
                if (p.strip().equalsIgnoreCase("prod")) {
                    return "Profil prod aktiv - unsignierte Module nie auf PROD";
                }
            }
        }
        if (z.index() == null || !"file".equals(z.index().getScheme())) {
            return "nur aus einer file:-Registry, nicht aus " + z.index();
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------ Aufloesen

    /**
     * Legt je Wahl die Zeile fest (feste Version oder {@code latest}) und loest {@code braucht} auf.
     * Jede gewaehlte Zeile muss gueltig signiert sein und zur Huelle passen, sonst Fehler (kein stilles
     * Ausweichen auf eine aeltere Version).
     */
    static List<Zeile> aufloesen(List<Wahl> wahl, Angebot angebot, List<PublicKey> schluessel,
                                 ModulLader.Huelle huelle, boolean latestErlaubt) {
        Map<String, Zeile> ergebnis = new LinkedHashMap<>();
        for (Wahl w : wahl) {
            List<Zeile> kandidaten = angebot.zeilen().stream()
                    .filter(z -> z.istModul() && z.name().equals(w.name())).toList();
            Zeile z;
            if (LATEST.equals(w.version())) {
                if (!latestErlaubt) {
                    throw new IllegalArgumentException("'" + w.name() + "': 'latest' ist hier nicht erlaubt (festes Image braucht feste Versionen)");
                }
                z = kandidaten.stream().filter(k -> rootMinPasst(k.rootMin(), huelle) == null)
                        .max(Comparator.comparing(Zeile::version, ModulAuswahl::versionVergleich))
                        .orElseThrow(() -> new IllegalStateException("'" + w.name() + "' latest: keine zur Huelle "
                                + huelle.version() + " passende Version in den Registries" + fehlerText(angebot)));
            } else {
                z = kandidaten.stream().filter(k -> k.version().equals(w.version())).findFirst()
                        .orElseThrow(() -> new IllegalStateException("'" + w.name() + "' " + w.version()
                                + " steht in keiner Registry" + fehlerText(angebot)));
                String grund = rootMinPasst(z.rootMin(), huelle);
                if (grund != null) {
                    throw new IllegalStateException("'" + w.name() + "' " + w.version() + ": " + grund);
                }
            }
            hinzufuegen(z, angebot, schluessel, ergebnis);
        }
        List<Zeile> mitBibliotheken = new ArrayList<>(ergebnis.values());
        mitBibliotheken.addAll(bibliotheken(List.copyOf(ergebnis.values()), angebot.zeilen()));
        return List.copyOf(mitBibliotheken);
    }

    /**
     * Fremd-Bibliotheken der Module: Ort aus der {@code bib}-Zeile derselben Registry, sha256 aus der signierten
     * Liste des Moduls. Zwei Module mit derselben Bibliothek in verschiedenen Versionen (oder Hashes): Abbruch,
     * nie still eine nehmen.
     */
    static List<Zeile> bibliotheken(List<Zeile> module, List<Zeile> alle) {
        Map<String, Zeile> je = new LinkedHashMap<>();
        Map<String, String> von = new LinkedHashMap<>();
        for (Zeile m : module) {
            for (String b : m.bibliotheken()) {
                java.util.regex.Matcher x = BIBLIOTHEK.matcher(b);
                if (!x.matches()) {
                    throw new IllegalStateException("'" + m.name() + "': Bibliothek '" + b + "' unlesbar");
                }
                String ga = x.group(1) + ":" + x.group(2);
                Zeile da = je.get(ga);
                if (da != null) {
                    if (!da.version().equals(x.group(3)) || !da.sha256().equals(x.group(4))) {
                        throw new IllegalStateException("Versionskonflikt: Bibliothek " + ga + " verlangt '" + von.get(ga)
                                + "' als " + da.version() + ", '" + m.name() + "' als " + x.group(3)
                                + " - ein gemeinsamer Klassenpfad kann nur eine haben");
                    }
                    continue;
                }
                Zeile ort = alle.stream().filter(z -> z.istBibliothek() && z.name().equals(ga)
                                && z.version().equals(x.group(3)) && z.registry().equals(m.registry())).findFirst()
                        .orElseThrow(() -> new IllegalStateException("'" + m.name() + "' braucht Bibliothek " + ga + ":"
                                + x.group(3) + ", Registry '" + m.registry() + "' hat dafuer keine 'bib'-Zeile"));
                je.put(ga, new Zeile("bib", ga, x.group(3), null, x.group(4), List.of(), List.of(), ort.datei(), null,
                        ort.registry(), ort.index()));
                von.put(ga, m.name());
            }
        }
        return List.copyOf(je.values());
    }

    /**
     * Liefert die Huelle dieselbe Bibliothek, gilt ihre Fassung: gleiche Version = nicht nachladen, andere Version =
     * Abbruch mit klarer Meldung (frueher: still uebersprungen, z.B. jcommander 1.72 statt 1.85).
     */
    static List<Zeile> gegenHuelle(List<Zeile> zeilen, Path huelle) throws IOException {
        if (huelle == null || !Files.isRegularFile(huelle)) {
            return zeilen;
        }
        Map<String, String> libs = new LinkedHashMap<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(huelle.toFile())) {
            zip.stream().map(java.util.zip.ZipEntry::getName)
                    .filter(n -> n.startsWith("BOOT-INF/lib/") && n.endsWith(".jar"))
                    .forEach(n -> libs.put(n.substring(13, n.length() - 4), n));
        }
        List<Zeile> out = new ArrayList<>();
        for (Zeile z : zeilen) {
            if (!z.istBibliothek()) {
                out.add(z);
                continue;
            }
            if (libs.containsKey(z.artefakt() + "-" + z.version())) {
                System.out.println("[modul-lader] Bibliothek " + z.name() + ":" + z.version() + " liefert die Huelle");
                continue;
            }
            for (String l : libs.keySet()) {
                if (l.startsWith(z.artefakt() + "-") && l.length() > z.artefakt().length() + 1
                        && Character.isDigit(l.charAt(z.artefakt().length() + 1))) {
                    throw new IllegalStateException("Versionskonflikt: Bibliothek " + z.name() + " verlangt " + z.version()
                            + ", die Huelle liefert " + l.substring(z.artefakt().length() + 1) + " - nicht geladen");
                }
            }
            out.add(z);
        }
        return List.copyOf(out);
    }

    private static void hinzufuegen(Zeile z, Angebot angebot, List<PublicKey> schluessel, Map<String, Zeile> ergebnis) {
        signaturPruefen(z, schluessel);
        Zeile da = ergebnis.get(z.name());
        if (da != null) {
            if (!da.version().equals(z.version())) {
                throw new IllegalStateException("'" + z.name() + "' zweimal in verschiedenen Versionen (" + da.version()
                        + ", " + z.version() + ")");
            }
            return;
        }
        ergebnis.put(z.name(), z);
        for (String b : z.braucht()) {
            String[] nv = b.split(":");
            Zeile dep = angebot.zeilen().stream()
                    .filter(k -> k.name().equals(nv[0]) && k.version().equals(nv[1])).findFirst()
                    .orElseThrow(() -> new IllegalStateException("'" + z.name() + "' braucht " + b
                            + ", das steht in keiner Registry" + fehlerText(angebot)));
            hinzufuegen(dep, angebot, schluessel, ergebnis);
        }
    }

    private static String fehlerText(Angebot a) {
        return a.fehler().isEmpty() ? "" : " (Registry-Fehler: " + a.fehler() + ")";
    }

    /**
     * null = passt, sonst der Grund. Regel aus 1598: Eine v2-Huelle 2.M.m beruht auf v1 1.M.0; ein 1.x-root-min
     * muss darum &lt;= 1.M.0 sein. Dazu &gt;= Kompatibel-Ab.
     */
    public static String rootMinPasst(String rootMin, ModulLader.Huelle huelle) {
        int[] r = zahlen(rootMin);
        int[] h = zahlen(huelle.version());
        if (r[0] == 1 && h[0] == 2) {
            if (ModulKonfig.vergleiche(rootMin, "1." + h[1] + ".0") > 0) {
                return "root-min " + rootMin + " > v1-Basis 1." + h[1] + ".0 der Huelle " + huelle.version();
            }
        } else if (r[0] == h[0]) {
            if (ModulKonfig.vergleiche(rootMin, huelle.version()) > 0) {
                return "root-min " + rootMin + " > Huelle " + huelle.version();
            }
        } else {
            return "root-min " + rootMin + " passt zu keiner Linie der Huelle " + huelle.version();
        }
        if (ModulKonfig.vergleiche(rootMin, huelle.kompatibelAb()) < 0) {
            return "root-min " + rootMin + " < Kompatibel-Ab " + huelle.kompatibelAb();
        }
        return null;
    }

    private static int[] zahlen(String v) {
        String[] t = v.split("\\.");
        return new int[]{Integer.parseInt(t[0]), Integer.parseInt(t[1]), Integer.parseInt(t[2])};
    }

    /** Vergleicht Versionen Teil fuer Teil, Zahlen numerisch (2.2028.0 &lt; 3.0.0, 3.0.10 &gt; 3.0.9). */
    public static int versionVergleich(String a, String b) {
        String[] x = a.split("[.-]");
        String[] y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "0";
            String q = i < y.length ? y[i] : "0";
            int c = p.matches("\\d+") && q.matches("\\d+")
                    ? new java.math.BigInteger(p).compareTo(new java.math.BigInteger(q)) : p.compareTo(q);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------------------------------ Laden

    /** Ziel der Datei einer Zeile; muss am Ursprung (Schema, Host, Port) ihres Index bleiben. */
    static URI adresse(Zeile z) {
        URI ziel = z.index().resolve(z.datei()).normalize();
        URI i = z.index();
        if (!i.getScheme().equals(ziel.getScheme()) || !java.util.Objects.equals(i.getHost(), ziel.getHost())
                || i.getPort() != ziel.getPort()) {
            throw new IllegalArgumentException("'" + z.name() + "': Datei ausserhalb der Registry: " + ziel);
        }
        return ziel;
    }

    /** Jar in den Cache (oder aus dem Cache), sha256 gegen die signierte Zeile. */
    static Path bereitstellen(Zeile z, Path cache, ModulQuelle quelle, boolean herunterladen)
            throws IOException, InterruptedException {
        Path ziel = cache.resolve(z.dateiname());
        if (Files.isRegularFile(ziel)) {
            if (ModulQuelle.sha256(ziel).equals(z.sha256())) {
                return ziel;
            }
            if (!herunterladen) {
                throw new IllegalStateException(z.dateiname() + ": sha256 passt nicht zur signierten Zeile");
            }
            Files.delete(ziel);
        } else if (!herunterladen) {
            throw new IllegalStateException(z.dateiname() + " fehlt");
        }
        Path temp = cache.resolve("." + z.dateiname() + "." + UUID.randomUUID() + ".tmp");
        try {
            String ist = quelle.laden(adresse(z), temp, "Modul '" + z.name() + "'");
            if (!ist.equals(z.sha256())) {
                throw new IllegalStateException("'" + z.name() + "' " + z.version() + ": sha256 " + ist
                        + " passt nicht zur signierten Zeile (" + z.sha256() + ") - nicht geladen");
            }
            Files.move(temp, ziel, StandardCopyOption.ATOMIC_MOVE);
            return ziel;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Eintraege fuer die bestehenden Pruefungen (api/braucht-Ziele sind Bibliotheken). */
    static List<ModulKonfig.Eintrag> eintraege(List<Zeile> zeilen) {
        Set<String> gebraucht = new LinkedHashSet<>();
        zeilen.forEach(z -> z.braucht().forEach(b -> gebraucht.add(b.split(":")[0])));
        return zeilen.stream().map(z -> z.eintrag(z.istBibliothek() || gebraucht.contains(z.name()))).toList();
    }

    static String indexText(List<Zeile> zeilen, String kommentar) {
        StringBuilder sb = new StringBuilder(INDEX_KOPF).append("\n# ").append(kommentar).append('\n');
        zeilen.forEach(z -> sb.append(z.alsIndexZeile()).append('\n'));
        return sb.toString();
    }

    static void atomarSchreiben(Path ziel, String text) throws IOException {
        Path temp = ziel.resolveSibling("." + ziel.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            Files.move(temp, ziel, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** {@code status.txt}: was beim letzten Start geschah (liest die Seite). */
    static void statusSchreiben(Path verzeichnis, String ergebnis, String grund, List<Zeile> geladen) {
        String text = "zeit=" + OffsetDateTime.now() + "\nergebnis=" + ergebnis + "\ngrund="
                + (grund == null ? "" : grund.replaceAll("[\r\n]", " ")) + "\ngeladen="
                + String.join(",", geladen.stream().map(z -> z.name() + ":" + z.version()).toList()) + "\n";
        try {
            atomarSchreiben(verzeichnis.resolve(STATUS), text);
        } catch (IOException ex) {
            System.out.println("[modul-lader] " + STATUS + " nicht schreibbar: " + ex.getMessage());
        }
    }

    /** Liest {@code status.txt} als Schluessel/Wert (fuer die Seite); leer, wenn es sie nicht gibt. */
    public static Map<String, String> statusLesen(Path verzeichnis) throws IOException {
        Path p = verzeichnis.resolve(STATUS);
        Map<String, String> m = new LinkedHashMap<>();
        if (Files.isRegularFile(p)) {
            for (String z : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                int i = z.indexOf('=');
                if (i > 0) {
                    m.put(z.substring(0, i), z.substring(i + 1));
                }
            }
        }
        return m;
    }

    static Optional<String> lesen(Path p) throws IOException {
        return Files.isRegularFile(p) ? Optional.of(Files.readString(p, StandardCharsets.UTF_8)) : Optional.empty();
    }

    private static String pruefe(String wert, Pattern muster, String was, String wo) {
        if (wert == null || !muster.matcher(wert).matches()) {
            throw new IllegalArgumentException(wo + ": ungueltiger Wert fuer '" + was + "': " + wert);
        }
        return wert;
    }

    private static String ohneKommentar(String zeile) {
        int i = zeile.indexOf('#');
        return (i >= 0 ? zeile.substring(0, i) : zeile).stripTrailing();
    }
}

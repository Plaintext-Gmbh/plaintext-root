/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules.katalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Karte 1405: Katalog der öffentlichen Modul-Schnittstellen dieser Anwendung, für ein LLM, das neue
 * Funktionen aus vorhandenen Bausteinen plant (Daniel 02.10.2026).
 *
 * <p>Die Beschreibungen schreibt der Annotation-Prozessor {@code plaintext-root-katalog} beim Bau der
 * {@code *-interfaces}-Module als {@code META-INF/plaintext-katalog/<modul>.json} ins Jar (Javadoc als
 * Zweck). Hier werden alle Kataloge vom Klassenpfad gelesen und mit den laufenden Spring-Beans
 * verknüpft: welche Bean setzt die Schnittstelle um, und aus welchem Modul-Jar stammt sie.</p>
 */
@Slf4j
@Service
public class SchnittstellenKatalog {

    static final String MUSTER = "classpath*:META-INF/plaintext-katalog/*.json";
    /** Card 1438: framework functionality of plaintext-root ({@code plaintext-root-*}, {@code plaintext-admin-*}). */
    public static final String ROOT = "root";
    /** Card 1438: functionality of an app module ({@code plaintext-z-*}, {@code plaintext-guild-*}, …). */
    public static final String MODUL = "modul";
    private static final ObjectMapper JSON = JsonMapper.builderWithJackson2Defaults().build();

    private final ApplicationContext kontext;
    /** Einmal gelesen, dann unveraendert; AtomicReference statt volatile (Karte 1416, Sonar java:S3077). */
    private final java.util.concurrent.atomic.AtomicReference<Stand> zwischenspeicher =
            new java.util.concurrent.atomic.AtomicReference<>();

    public SchnittstellenKatalog(ApplicationContext kontext) {
        this.kontext = kontext;
    }

    /** Eine Methode einer Schnittstelle. */
    public record Methode(String name, String rueckgabe, String art, String zweck, List<Parameter> parameter) {
    }

    /** Ein Parameter. */
    public record Parameter(String name, String typ) {
    }

    /**
     * Wer eine Schnittstelle umsetzt: Bean-Name, Klasse, Modul-Jar und — falls die Klasse
     * {@code @ModulApiUmsetzung} trägt — ihre Beschreibung (Karte 1422), sonst {@code null}.
     */
    public record Umsetzer(String bean, String klasse, String modul, Umsetzung beschreibung) {

        /** @return {@link #ROOT} or {@link #MODUL} for {@link #modul()} (card 1438) */
        @JsonProperty("ebene")
        public String ebene() {
            return SchnittstellenKatalog.ebene(modul);
        }
    }

    /**
     * Karte 1422: wie eine Klasse einen Vertrag umsetzt, aus {@code @ModulApiUmsetzung} (Klasse und
     * einzelne Methoden). {@code modul} ist das Modul, in dessen Katalog die Beschreibung steht.
     */
    public record Umsetzung(String modul, String klasse, String kurz, List<String> schnittstellen,
                            String beschreibung, String seiteneffekte, List<String> hinweise,
                            List<String> beispiele, List<MethodenUmsetzung> methoden) {

        /** @return {@link #ROOT} or {@link #MODUL} for {@link #modul()} (card 1438) */
        @JsonProperty("ebene")
        public String ebene() {
            return SchnittstellenKatalog.ebene(modul);
        }
    }

    /** Eigene Beschreibung einer einzelnen Methode einer Umsetzung. */
    public record MethodenUmsetzung(String name, String beschreibung, String seiteneffekte,
                                    List<String> hinweise, List<String> beispiele) {
    }

    /**
     * Eine Schnittstelle mit Zweck, Methoden und Umsetzern. Karte 1422: {@code annotiert} = trägt
     * {@code @ModulApi}; {@code art} {@code SCHNITTSTELLE}/{@code DTO} (leer ohne Annotation);
     * {@code stabilitaet} {@code STABIL}/{@code NEU}/{@code VERALTET}; {@code herkunft}
     * «interfaces-Modul» oder «im Modul»; {@code typ} {@code interface} oder {@code record} (Werte-DTO).
     */
    public record Schnittstelle(String modul, String name, String kurz, String zweck, List<String> erweitert,
                                List<Methode> methoden, List<Umsetzer> umsetzer, boolean annotiert, String art,
                                String stabilitaet, String seit, String ersatz, String herkunft, String typ) {

        /** @return {@code true} für ein zwischen Modulen übergebenes Model ({@code @ModulApi(art = DTO)}) */
        public boolean istDto() {
            return "DTO".equals(art);
        }

        /** @return {@link #ROOT} for a contract of plaintext-root, {@link #MODUL} for one of an app module (card 1438) */
        @JsonProperty("ebene")
        public String ebene() {
            return SchnittstellenKatalog.ebene(modul);
        }

        /** @return der erste Satz des Zwecks (für Übersichten) */
        public String zweckKurz() {
            String z = zweck == null ? "" : zweck.replaceAll("\\s+", " ").strip();
            int i = z.indexOf(". ");
            return i > 0 ? z.substring(0, i + 1) : z;
        }
    }

    /** @return alle Schnittstellen, nach Name; einmal gelesen und dann gehalten */
    public List<Schnittstelle> alle() {
        return stand().schnittstellen();
    }

    /** @return alle beschriebenen Umsetzungen ({@code @ModulApiUmsetzung}), nach Klasse (Karte 1422) */
    public List<Umsetzung> umsetzungen() {
        return stand().umsetzungen();
    }

    /** Gelesener Stand: Schnittstellen und Umsetzungen aus allen Katalogen. */
    record Stand(List<Schnittstelle> schnittstellen, List<Umsetzung> umsetzungen) {
    }

    private Stand stand() {
        Stand st = zwischenspeicher.get();
        if (st == null) {
            st = lies();
            zwischenspeicher.set(st);
        }
        return st;
    }

    /**
     * Card 1438 (Daniel 08.10.2026: "root or module?"): every module of plaintext-root is named
     * {@code plaintext-root-*} or {@code plaintext-admin-*}, and no app uses these prefixes.
     *
     * @param modul jar name without version, e.g. {@code plaintext-admin-sidecars}
     * @return {@link #ROOT} or {@link #MODUL}
     */
    public static String ebene(String modul) {
        String m = modul == null ? "" : modul;
        return m.startsWith("plaintext-root-") || m.startsWith("plaintext-admin-") ? ROOT : MODUL;
    }

    /**
     * Card 1438: filter by {@link #ebene(String)}; empty or {@code null} keeps everything.
     *
     * @param ebene {@code root}, {@code modul} or empty
     */
    public static List<Schnittstelle> nachEbene(List<Schnittstelle> l, String ebene) {
        String e = ebene == null ? "" : ebene.strip().toLowerCase(Locale.ROOT);
        return e.isEmpty() ? l : l.stream().filter(s -> s.ebene().equals(e)).toList();
    }

    /** @param name voller oder kurzer Name */
    public Optional<Schnittstelle> eine(String name) {
        String n = name == null ? "" : name.strip();
        return alle().stream().filter(s -> s.name().equals(n) || s.kurz().equalsIgnoreCase(n)).findFirst();
    }

    /** Volltextsuche in Name, Zweck, Methodennamen und deren Zweck. */
    public List<Schnittstelle> suche(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT).strip();
        if (t.isEmpty()) {
            return alle();
        }
        return alle().stream().filter(s -> {
            StringBuilder h = new StringBuilder(s.name()).append(' ').append(s.zweck());
            s.methoden().forEach(m -> h.append(' ').append(m.name()).append(' ').append(m.zweck()));
            return h.toString().toLowerCase(Locale.ROOT).contains(t);
        }).toList();
    }

    Stand lies() {
        List<JsonNode> kataloge = new ArrayList<>();
        try {
            Resource[] rs = new PathMatchingResourcePatternResolver(getClass().getClassLoader()).getResources(MUSTER);
            for (Resource r : rs) {
                try (InputStream in = r.getInputStream()) {
                    kataloge.add(JSON.readTree(in));
                } catch (IOException | RuntimeException e) {
                    log.warn("Schnittstellen-Katalog {} nicht lesbar: {}", r, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Schnittstellen-Kataloge nicht auffindbar: {}", e.getMessage());
        }
        // Erst alle Umsetzungen, damit jede Schnittstelle ihre Umsetzer samt Beschreibung bekommt —
        // die Beschreibung steht im Katalog des umsetzenden Moduls, nicht in dem des Vertrags.
        List<Umsetzung> umsetzungen = new ArrayList<>();
        for (JsonNode k : kataloge) {
            String modul = k.path("modul").asString();
            k.path("umsetzungen").forEach(u -> umsetzungen.add(umsetzung(modul, u)));
        }
        umsetzungen.sort(Comparator.comparing(Umsetzung::klasse));
        java.util.Map<String, Umsetzung> nachKlasse = new java.util.HashMap<>();
        umsetzungen.forEach(u -> nachKlasse.putIfAbsent(binaerName(u.klasse()), u));
        List<Schnittstelle> l = new ArrayList<>();
        for (JsonNode k : kataloge) {
            String modul = k.path("modul").asString();
            for (JsonNode s : k.path("schnittstellen")) {
                l.add(schnittstelle(modul, s, nachKlasse));
            }
        }
        l.sort(Comparator.comparing(Schnittstelle::name));
        return new Stand(List.copyOf(l), List.copyOf(umsetzungen));
    }

    private static Umsetzung umsetzung(String modul, JsonNode u) {
        List<MethodenUmsetzung> m = new ArrayList<>();
        u.path("methoden").forEach(x -> m.add(new MethodenUmsetzung(x.path("name").asString(), x.path("beschreibung").asString(),
                x.path("seiteneffekte").asString(), texte(x.path("hinweise")), texte(x.path("beispiele")))));
        return new Umsetzung(modul, u.path("klasse").asString(), u.path("kurz").asString(), texte(u.path("schnittstellen")),
                u.path("beschreibung").asString(), u.path("seiteneffekte").asString(), texte(u.path("hinweise")),
                texte(u.path("beispiele")), List.copyOf(m));
    }

    private static List<String> texte(JsonNode liste) {
        List<String> l = new ArrayList<>();
        liste.forEach(x -> l.add(x.asString()));
        return List.copyOf(l);
    }

    private Schnittstelle schnittstelle(String modul, JsonNode s, java.util.Map<String, Umsetzung> beschreibungen) {
        List<String> erweitert = new ArrayList<>();
        s.path("erweitert").forEach(x -> erweitert.add(x.asString()));
        List<Methode> methoden = new ArrayList<>();
        for (JsonNode m : s.path("methoden")) {
            List<Parameter> p = new ArrayList<>();
            m.path("parameter").forEach(x -> p.add(new Parameter(x.path("name").asString(), x.path("typ").asString())));
            methoden.add(new Methode(m.path("name").asString(), m.path("rueckgabe").asString(), m.path("art").asString(),
                    m.path("zweck").asString(), List.copyOf(p)));
        }
        String name = s.path("name").asString();
        String herkunft = s.path("herkunft").asString(modul.endsWith("-interfaces") ? "interfaces-Modul" : "im Modul");
        return new Schnittstelle(modul, name, s.path("kurz").asString(), s.path("zweck").asString(),
                List.copyOf(erweitert), List.copyOf(methoden), umsetzer(name, beschreibungen),
                s.path("annotiert").asBoolean(false), s.path("art").asString(""), s.path("stabilitaet").asString(""),
                s.path("seit").asString(""), s.path("ersatz").asString(""), herkunft, s.path("typ").asString("interface"));
    }

    /** Beans, die die Schnittstelle umsetzen; ohne Laden fremder Klassen, wenn die Schnittstelle fehlt. */
    List<Umsetzer> umsetzer(String name) {
        return umsetzer(name, java.util.Map.of());
    }

    private List<Umsetzer> umsetzer(String name, java.util.Map<String, Umsetzung> beschreibungen) {
        Class<?> typ;
        try {
            typ = Class.forName(binaerName(name), false, getClass().getClassLoader());
        } catch (ClassNotFoundException | LinkageError _) {
            return List.of();
        }
        List<Umsetzer> u = new ArrayList<>();
        for (String bean : kontext.getBeanNamesForType(typ, true, false)) {
            Class<?> k = kontext.getType(bean);
            k = k == null ? null : org.springframework.util.ClassUtils.getUserClass(k);
            if (k != null) {
                u.add(new Umsetzer(bean, k.getName(), modul(k), beschreibungen.get(k.getName())));
            }
        }
        u.sort(Comparator.comparing(Umsetzer::bean));
        return List.copyOf(u);
    }

    /** {@code a.b.Aussen.Innen} → {@code a.b.Aussen$Innen} für verschachtelte Schnittstellen. */
    static String binaerName(String name) {
        String[] teile = name.split("\\.");
        StringBuilder b = new StringBuilder();
        boolean klasse = false;
        for (int i = 0; i < teile.length; i++) {
            if (i > 0) {
                b.append(klasse ? '$' : '.');
            }
            b.append(teile[i]);
            if (!teile[i].isEmpty() && Character.isUpperCase(teile[i].charAt(0))) {
                klasse = true;
            }
        }
        return b.toString();
    }

    /** @return Jar-Name ohne Version, z. B. {@code plaintext-z-fotos}, oder {@code ?} */
    public static String modul(Class<?> k) {
        try {
            CodeSource cs = k.getProtectionDomain().getCodeSource();
            URL u = cs == null ? null : cs.getLocation();
            if (u == null) {
                return "?";
            }
            return modulAusOrt(u.toString());
        } catch (RuntimeException _) {
            return "?";
        }
    }

    /**
     * Modulname aus dem Ort einer Klasse: Jar ohne Version, oder im Build das Verzeichnis vor
     * {@code /target/classes}. Reine String-Arbeit statt regulaerer Ausdruecke (Karte 1416, Sonar
     * java:S5852/S5998): {@code .*} vor einer Gruppe lief in polynomieller Zeit zurueck.
     */
    static String modulAusOrt(String ort) {
        int jar = ort.lastIndexOf(".jar");
        if (jar < 0) {
            int t = ort.lastIndexOf("/target/classes");
            if (t <= 0) {
                return "?";
            }
            String vor = ort.substring(0, t);
            String name = vor.substring(vor.lastIndexOf('/') + 1);
            return name.isEmpty() ? "?" : name;
        }
        return ohneVersion(ort.substring(ort.lastIndexOf('/', jar) + 1, jar));
    }

    /** {@code plaintext-z-fotos-2.1905.0-SNAPSHOT} → {@code plaintext-z-fotos}; ohne Version unveraendert. */
    static String ohneVersion(String datei) {
        String d = datei.endsWith("-SNAPSHOT") ? datei.substring(0, datei.length() - "-SNAPSHOT".length()) : datei;
        int strich = d.lastIndexOf('-');
        if (strich < 0 || !istVersion(d.substring(strich + 1))) {
            return datei;
        }
        return d.substring(0, strich);
    }

    /** Ziffern, durch einzelne Punkte getrennt, ohne Punkt am Anfang oder Ende. */
    private static boolean istVersion(String v) {
        if (v.isEmpty() || v.charAt(0) == '.' || v.charAt(v.length() - 1) == '.') {
            return false;
        }
        char vorher = ' ';
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '.' && vorher == '.') {
                return false;
            }
            if (c != '.' && (c < '0' || c > '9')) {
                return false;
            }
            vorher = c;
        }
        return true;
    }
}

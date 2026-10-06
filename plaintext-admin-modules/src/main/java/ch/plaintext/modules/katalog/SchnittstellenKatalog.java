/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules.katalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ApplicationContext kontext;
    /** Einmal gelesen, dann unveraendert; AtomicReference statt volatile (Karte 1416, Sonar java:S3077). */
    private final java.util.concurrent.atomic.AtomicReference<List<Schnittstelle>> zwischenspeicher =
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

    /** Wer eine Schnittstelle umsetzt: Bean-Name, Klasse, Modul-Jar. */
    public record Umsetzer(String bean, String klasse, String modul) {
    }

    /** Eine Schnittstelle mit Zweck, Methoden und Umsetzern. */
    public record Schnittstelle(String modul, String name, String kurz, String zweck, List<String> erweitert,
                                List<Methode> methoden, List<Umsetzer> umsetzer) {

        /** @return der erste Satz des Zwecks (für Übersichten) */
        public String zweckKurz() {
            String z = zweck == null ? "" : zweck.replaceAll("\\s+", " ").strip();
            int i = z.indexOf(". ");
            return i > 0 ? z.substring(0, i + 1) : z;
        }
    }

    /** @return alle Schnittstellen, nach Name; einmal gelesen und dann gehalten */
    public List<Schnittstelle> alle() {
        List<Schnittstelle> l = zwischenspeicher.get();
        if (l == null) {
            l = lies();
            zwischenspeicher.set(l);
        }
        return l;
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

    List<Schnittstelle> lies() {
        List<Schnittstelle> l = new ArrayList<>();
        try {
            Resource[] rs = new PathMatchingResourcePatternResolver(getClass().getClassLoader()).getResources(MUSTER);
            for (Resource r : rs) {
                try (InputStream in = r.getInputStream()) {
                    JsonNode k = JSON.readTree(in);
                    String modul = k.path("modul").asText();
                    for (JsonNode s : k.path("schnittstellen")) {
                        l.add(schnittstelle(modul, s));
                    }
                } catch (IOException | RuntimeException e) {
                    log.warn("Schnittstellen-Katalog {} nicht lesbar: {}", r, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Schnittstellen-Kataloge nicht auffindbar: {}", e.getMessage());
        }
        l.sort(Comparator.comparing(Schnittstelle::name));
        return List.copyOf(l);
    }

    private Schnittstelle schnittstelle(String modul, JsonNode s) {
        List<String> erweitert = new ArrayList<>();
        s.path("erweitert").forEach(x -> erweitert.add(x.asText()));
        List<Methode> methoden = new ArrayList<>();
        for (JsonNode m : s.path("methoden")) {
            List<Parameter> p = new ArrayList<>();
            m.path("parameter").forEach(x -> p.add(new Parameter(x.path("name").asText(), x.path("typ").asText())));
            methoden.add(new Methode(m.path("name").asText(), m.path("rueckgabe").asText(), m.path("art").asText(),
                    m.path("zweck").asText(), List.copyOf(p)));
        }
        String name = s.path("name").asText();
        return new Schnittstelle(modul, name, s.path("kurz").asText(), s.path("zweck").asText(),
                List.copyOf(erweitert), List.copyOf(methoden), umsetzer(name));
    }

    /** Beans, die die Schnittstelle umsetzen; ohne Laden fremder Klassen, wenn die Schnittstelle fehlt. */
    List<Umsetzer> umsetzer(String name) {
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
                u.add(new Umsetzer(bean, k.getName(), modul(k)));
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
    static String modul(Class<?> k) {
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

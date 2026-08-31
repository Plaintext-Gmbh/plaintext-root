/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Status report 29.08.2026 (H4): page permissions live in two places — hard-wired in
 * {@code PlaintextSecurityConfig} (ROOT_ONLY_PAGES / ADMIN_PAGES, what Spring lets through) and
 * per menu entry in {@code @MenuAnnotation(roles = …)} (what the menu guard shows). When the two
 * drift apart, there are pages that nobody reaches (oidcconfig with roles={"root"}) or
 * that an authorized user does not see in the menu (cron ADMIN only, although ROOT is allowed).
 *
 * <p>This test reads both places and records three rules:</p>
 * <ol>
 *   <li>Roles in {@code @MenuAnnotation} are written in UPPER CASE — the comparison is case-sensitive.</li>
 *   <li>A menu entry pointing to a ROOT_ONLY page requires exactly {ROOT}.</li>
 *   <li>A menu entry pointing to an ADMIN page requires exactly {ADMIN, ROOT} — both places say the same.</li>
 * </ol>
 *
 * <p>Reads the sources of all modules (not the classpath), so that menus from
 * plaintext-admin-* are covered as well, which root-webapp does not have as a test dependency.</p>
 */
class SeitenrechteInvariantTest {

    private static final Pattern ROLLE_OK = Pattern.compile("^[A-Z][A-Z_]*$");
    private static final Pattern LINK = Pattern.compile("\\blink\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ROLES = Pattern.compile("\\broles\\s*=\\s*\\{([^}]*)\\}");
    private static final Pattern STRING = Pattern.compile("\"([^\"]*)\"");

    /** link → (file, roles) for every @MenuAnnotation in the repository. */
    private static final Map<String, MenuEintrag> MENUES = new LinkedHashMap<>();

    record MenuEintrag(String datei, String link, Set<String> rollen) {
    }

    @BeforeAll
    static void menuesSammeln() throws IOException {
        Path repoRoot = findeRepoRoot();
        try (Stream<Path> dateien = Files.walk(repoRoot)) {
            List<Path> javaDateien = dateien
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .toList();
            for (Path datei : javaDateien) {
                String quelle = Files.readString(datei);
                for (String block : annotationsBloecke(quelle)) {
                    Matcher l = LINK.matcher(block);
                    if (!l.find()) {
                        continue;
                    }
                    Set<String> rollen = new TreeSet<>();
                    Matcher r = ROLES.matcher(block);
                    if (r.find()) {
                        Matcher st = STRING.matcher(r.group(1));
                        while (st.find()) {
                            rollen.add(st.group(1));
                        }
                    }
                    String link = l.group(1);
                    MENUES.put(repoRoot.relativize(datei) + "#" + link,
                            new MenuEintrag(repoRoot.relativize(datei).toString(), link, rollen));
                }
            }
        }
        assertFalse(MENUES.isEmpty(), "keine @MenuAnnotation gefunden — Scanner kaputt?");
    }

    @Test
    @DisplayName("Rollen in @MenuAnnotation sind GROSS geschrieben (Vergleich ist case-sensitiv)")
    void rollenGrossGeschrieben() {
        List<String> verstoesse = new ArrayList<>();
        for (MenuEintrag m : MENUES.values()) {
            for (String rolle : m.rollen()) {
                if (!ROLLE_OK.matcher(rolle).matches()) {
                    verstoesse.add(m.datei() + " (" + m.link() + "): roles enthaelt \"" + rolle + "\"");
                }
            }
        }
        assertTrue(verstoesse.isEmpty(), "Falsch geschriebene Rollen — die Seite ist fuer NIEMANDEN "
                + "erreichbar:\n" + String.join("\n", verstoesse));
    }

    @Test
    @DisplayName("Menuepunkte auf ROOT_ONLY_PAGES verlangen genau {ROOT}")
    void rootOnlySeitenNurRoot() {
        pruefe(seitenMuster("ROOT_ONLY_PAGES"), Set.of("ROOT"), "ROOT_ONLY_PAGES");
    }

    @Test
    @DisplayName("Menuepunkte auf ADMIN_PAGES verlangen genau {ADMIN, ROOT}")
    void adminSeitenAdminUndRoot() {
        pruefe(seitenMuster("ADMIN_PAGES"), Set.of("ADMIN", "ROOT"), "ADMIN_PAGES");
    }

    private static void pruefe(String[] muster, Set<String> erwartet, String liste) {
        List<String> verstoesse = new ArrayList<>();
        int getroffen = 0;
        for (MenuEintrag m : MENUES.values()) {
            if (!trifft(muster, m.link())) {
                continue;
            }
            getroffen++;
            if (!m.rollen().equals(erwartet)) {
                verstoesse.add(m.datei() + " (" + m.link() + "): roles=" + m.rollen()
                        + ", Security-Config (" + liste + ") sagt " + erwartet);
            }
        }
        assertTrue(getroffen > 0, "kein Menuepunkt trifft " + liste + " — Muster-Abgleich kaputt?");
        assertTrue(verstoesse.isEmpty(), "Menue-Rollen und " + liste + " widersprechen sich:\n"
                + String.join("\n", verstoesse));
    }

    /** Ant patterns such as "/mandate*.*" against the canonical link ("mandatemenu.html" → "/mandatemenu.html"). */
    static boolean trifft(String[] muster, String link) {
        String pfad = link.startsWith("/") ? link : "/" + link;
        for (String m : muster) {
            String regex = "^" + m.replace(".", "\\.").replace("*", "[^/]*") + "$";
            if (pfad.matches(regex)) {
                return true;
            }
        }
        return false;
    }

    private static String[] seitenMuster(String feldName) {
        try {
            Field f = PlaintextSecurityConfig.class.getDeclaredField(feldName);
            f.setAccessible(true);
            return (String[]) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Feld " + feldName + " in PlaintextSecurityConfig nicht gefunden", e);
        }
    }

    /** All @MenuAnnotation(...) blocks of a source, with brackets counted (values contain brackets). */
    static List<String> annotationsBloecke(String quelle) {
        List<String> bloecke = new ArrayList<>();
        int start = 0;
        while ((start = quelle.indexOf("@MenuAnnotation(", start)) >= 0) {
            int tiefe = 0;
            int i = quelle.indexOf('(', start);
            for (; i < quelle.length(); i++) {
                char c = quelle.charAt(i);
                if (c == '(') {
                    tiefe++;
                } else if (c == ')') {
                    tiefe--;
                    if (tiefe == 0) {
                        break;
                    }
                }
            }
            bloecke.add(quelle.substring(start, Math.min(i + 1, quelle.length())));
            start = i + 1;
        }
        return bloecke;
    }

    private static Path findeRepoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("plaintext-root-webapp"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new AssertionError("Repo-Root (Verzeichnis mit plaintext-root-webapp) nicht gefunden");
        }
        return p;
    }
}

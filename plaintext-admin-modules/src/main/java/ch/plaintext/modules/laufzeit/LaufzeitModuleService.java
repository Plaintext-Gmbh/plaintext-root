/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules.laufzeit;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.audit.DestructiveActionAuditService;
import ch.plaintext.lader.ModulLader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Laufzeit-Module Schritt 4 (Karte 1515, Konzept 1503 Abschnitt 4): die Soll-Liste der Module im PV schreiben,
 * die der {@link ModulLader} beim naechsten Start liest.
 *
 * <ul>
 *   <li><b>Nur ROOT</b>, hier im Dienst geprueft (nicht nur ueber Menue und Page-Guard).</li>
 *   <li>Das Verzeichnis kommt nur aus {@code PLAINTEXT_MODULE_VERZEICHNIS} (Standard {@code /data/module}),
 *       nie aus einer Anfrage; die Dateinamen sind fest.</li>
 *   <li>Vor dem Schreiben prueft der Parser des Laders den Text: was der Lader ablehnen wuerde, wird nicht
 *       geschrieben.</li>
 *   <li>Atomar: Temp-Datei im selben Verzeichnis, dann {@code ATOMIC_MOVE}. Der bisherige Stand wird
 *       {@code module.yaml.vorher} (Rollback).</li>
 *   <li>Jede Aenderung steht im Audit-Log ({@code destructive_action_audit}: wer/wann ueber SuperModel).</li>
 * </ul>
 * Quelle der Versionen und Hashes ist {@code registry.yaml} (plaintext-modules, Format 2) im selben Verzeichnis;
 * wie sie dorthin kommt, regelt der Deploy (Karte 1516).
 */
@Slf4j
@Service
public class LaufzeitModuleService {

    static final String REGISTRY = "registry.yaml";
    static final String ROLLE = "ROLE_ROOT";
    static final String KANAL = "UI";

    /** Ein ladbares Modul der Registry, neueste Version. */
    public record RegistryModul(String name, String art, String version, String sha256, String rootMin,
                                List<String> api, List<String> pflicht, List<String> optional,
                                List<Bibliothek> bibliotheken) implements Serializable {
    }

    public record Bibliothek(String gav, String sha256) implements Serializable {
    }

    /** Was die Ansicht zeigt. */
    public record Stand(String verzeichnis, String registryFehler, List<RegistryModul> registry,
                        List<String> konfig, String konfigFehler, List<String> vorher,
                        List<String> geladen, List<String> neuBeimStart, List<String> wegBeimStart)
            implements Serializable {
    }

    private final PlaintextSecurity security;
    private final DestructiveActionAuditService audit;
    private final Path verzeichnis;

    public LaufzeitModuleService(PlaintextSecurity security, DestructiveActionAuditService audit,
                                 @Value("${" + ModulLader.VERZEICHNIS_ENV + ":" + ModulLader.STANDARD_VERZEICHNIS + "}")
                                 String verzeichnis) {
        this.security = security;
        this.audit = audit;
        this.verzeichnis = Path.of(verzeichnis);
    }

    // ------------------------------------------------------------------------------------------- lesen

    public Stand stand() {
        nurRoot();
        String registryFehler = null;
        List<RegistryModul> registry = List.of();
        try {
            registry = registry();
        } catch (Exception ex) {
            registryFehler = ex.getMessage();
        }
        List<String> konfig = List.of();
        String konfigFehler = null;
        try {
            konfig = lesenUndPruefen(verzeichnis.resolve(ModulLader.KONFIG));
        } catch (Exception ex) {
            konfigFehler = ex.getMessage();
        }
        List<String> vorher;
        try {
            vorher = lesenUndPruefen(verzeichnis.resolve(ModulLader.KONFIG_VORHER));
        } catch (Exception ex) {
            vorher = List.of("(unlesbar: " + ex.getMessage() + ")");
        }
        List<String> geladen = geladen();
        List<String> soll = konfig.stream().map(LaufzeitModuleService::dateiname).toList();
        return new Stand(verzeichnis.toString(), registryFehler, registry, konfig, konfigFehler, vorher, geladen,
                soll.stream().filter(s -> !geladen.contains(s)).toList(),
                geladen.stream().filter(g -> !soll.contains(g)).toList());
    }

    /** Ladbare Module der Registry mit Version; leer, wenn es keine {@code registry.yaml} gibt. */
    @SuppressWarnings("unchecked")
    List<RegistryModul> registry() throws IOException {
        Path datei = verzeichnis.resolve(REGISTRY);
        if (!Files.isRegularFile(datei)) {
            throw new IllegalStateException("keine " + datei);
        }
        Map<String, Object> wurzel = new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(Files.readString(datei, StandardCharsets.UTF_8));
        if (wurzel == null || !Integer.valueOf(2).equals(wurzel.get("format"))) {
            throw new IllegalStateException(datei + " ist nicht Format 2");
        }
        List<RegistryModul> out = new ArrayList<>();
        for (Map<String, Object> m : (List<Map<String, Object>>) wurzel.getOrDefault("module", List.of())) {
            String art = String.valueOf(m.get("art"));
            List<Map<String, Object>> versionen = (List<Map<String, Object>>) m.get("versionen");
            if (!("modul".equals(art) || "api".equals(art)) || versionen == null || versionen.isEmpty()) {
                continue;
            }
            Map<String, Object> v = versionen.get(0);
            List<Bibliothek> libs = new ArrayList<>();
            for (Map<String, Object> b : (List<Map<String, Object>>) v.getOrDefault("bibliotheken", List.of())) {
                libs.add(new Bibliothek(String.valueOf(b.get("gav")), String.valueOf(b.get("sha256"))));
            }
            out.add(new RegistryModul(String.valueOf(m.get("name")), art, String.valueOf(v.get("version")),
                    String.valueOf(v.get("sha256")), String.valueOf(v.get("root-min")), liste(v.get("api")),
                    liste(v.get("pflicht")), liste(v.get("optional")), List.copyOf(libs)));
        }
        return List.copyOf(out);
    }

    /**
     * Konfig fuer die Auswahl: api-Jars der gewaehlten Module kommen mit, PFLICHT-Vorbedingungen muessen
     * gewaehlt sein, die Stueckliste wird flach angehaengt. Gleiches Ergebnis wie {@code pv_konfig()} in
     * plaintext-modules/scripts/registry.py.
     */
    public String entwurf(Collection<String> namen) throws IOException {
        nurRoot();
        Map<String, RegistryModul> jeName = new LinkedHashMap<>();
        registry().forEach(m -> jeName.put(m.name(), m));
        Set<String> auswahl = new LinkedHashSet<>();
        List<String> offen = new ArrayList<>(namen);
        while (!offen.isEmpty()) {
            String n = offen.remove(0);
            if (!auswahl.add(n)) {
                continue;
            }
            RegistryModul m = jeName.get(n);
            if (m == null) {
                throw new IllegalArgumentException("'" + n + "' ist kein ladbares Modul der Registry");
            }
            offen.addAll(m.api());
        }
        for (String n : auswahl) {
            for (String p : jeName.get(n).pflicht()) {
                if (!auswahl.contains(p)) {
                    throw new IllegalArgumentException("'" + n + "' braucht '" + p + "' (Vorbedingung)");
                }
            }
        }
        StringBuilder sb = new StringBuilder("# geschrieben von der Modul-Ansicht (Karte 1515)\nmodule:\n");
        Map<String, String> libs = new LinkedHashMap<>();
        for (String n : auswahl) {
            RegistryModul m = jeName.get(n);
            sb.append("  - name: ").append(n).append('\n')
                    .append("    version: ").append(m.version()).append('\n')
                    .append("    sha256: ").append(m.sha256()).append('\n')
                    .append("    root-min: ").append(m.rootMin()).append('\n');
            if (!m.bibliotheken().isEmpty()) {
                sb.append("    bibliotheken: [")
                        .append(String.join(", ", m.bibliotheken().stream().map(Bibliothek::gav).toList()))
                        .append("]\n");
                for (Bibliothek b : m.bibliotheken()) {
                    String vorher = libs.putIfAbsent(b.gav(), b.sha256());
                    if (vorher != null && !vorher.equals(b.sha256())) {
                        throw new IllegalArgumentException(b.gav() + ": zwei verschiedene Hashes in der Registry");
                    }
                }
            }
        }
        libs.forEach((gav, sha) -> {
            String[] t = gav.split(":", -1);
            if (t.length != 3) {
                throw new IllegalArgumentException("Bibliothek '" + gav + "' ist nicht gruppe:name:version");
            }
            sb.append("  - name: ").append(t[1]).append('\n')
                    .append("    gruppe: ").append(t[0]).append('\n')
                    .append("    version: ").append(t[2]).append('\n')
                    .append("    sha256: ").append(sha).append('\n');
        });
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------- schreiben

    /** Schreibt die Auswahl als neue Konfig; wirksam beim naechsten Start. */
    public synchronized List<String> schreiben(Collection<String> namen) throws IOException {
        nurRoot();
        String text = entwurf(namen);
        List<String> neu = ModulLader.konfigPruefen(text); // wirft, wenn der Lader ablehnen wuerde
        Path konfig = verzeichnis.resolve(ModulLader.KONFIG);
        List<String> alt = nachsichtig(konfig); // eine kaputte Konfig darf man ueberschreiben
        Files.createDirectories(verzeichnis);
        if (Files.isRegularFile(konfig)) {
            atomarSchreiben(verzeichnis.resolve(ModulLader.KONFIG_VORHER), Files.readString(konfig));
        }
        atomarSchreiben(konfig, text);
        protokoll("LAUFZEIT_MODULE_SCHREIBEN", neu, alt);
        return neu;
    }

    /** Tauscht {@code module.yaml} und {@code module.yaml.vorher}; ein zweiter Rollback macht ihn rueckgaengig. */
    public synchronized List<String> rollback() throws IOException {
        nurRoot();
        Path konfig = verzeichnis.resolve(ModulLader.KONFIG);
        Path vorher = verzeichnis.resolve(ModulLader.KONFIG_VORHER);
        if (!Files.isRegularFile(vorher)) {
            throw new IllegalStateException("kein vorheriger Stand (" + vorher + ")");
        }
        String alterText = Files.readString(vorher);
        List<String> wieder = ModulLader.konfigPruefen(alterText);
        String jetzt = Files.isRegularFile(konfig) ? Files.readString(konfig) : null;
        List<String> ersetzt = nachsichtig(konfig);
        atomarSchreiben(konfig, alterText);
        if (jetzt != null) {
            atomarSchreiben(vorher, jetzt);
        } else {
            Files.delete(vorher);
        }
        protokoll("LAUFZEIT_MODULE_ROLLBACK", wieder, ersetzt);
        return wieder;
    }

    // ------------------------------------------------------------------------------------------- intern

    private void nurRoot() {
        if (!security.ifGranted(ROLLE)) {
            throw new SecurityException("Konfig der Laufzeit-Module: nur ROOT");
        }
    }

    private void atomarSchreiben(Path ziel, String text) throws IOException {
        Path temp = Files.createTempFile(verzeichnis, "." + ziel.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            Files.move(temp, ziel, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void protokoll(String aktion, List<String> neu, List<String> alt) {
        String wer = security.getUser();
        log.warn("{}: {} schreibt {} (vorher {}) in {}", aktion, wer, neu, alt, verzeichnis);
        audit.logDestructiveAction(KANAL, aktion, ModulLader.KONFIG, kurz(String.join(", ", neu)),
                kurz("Benutzer " + wer + "; vorher: " + String.join(", ", alt) + "; Verzeichnis " + verzeichnis));
    }

    private static String kurz(String s) {
        return s.length() <= 2000 ? s : s.substring(0, 1997) + "...";
    }

    private static List<String> nachsichtig(Path datei) throws IOException {
        try {
            return lesenUndPruefen(datei);
        } catch (IllegalArgumentException ex) {
            return List.of("(unlesbar: " + ex.getMessage() + ")");
        }
    }

    private static List<String> lesenUndPruefen(Path datei) throws IOException {
        return Files.isRegularFile(datei) ? ModulLader.konfigPruefen(Files.readString(datei)) : List.of();
    }

    /** Jars, mit denen diese Instanz gestartet wurde ({@code loader.path}, gesetzt vom ModulLader). */
    static List<String> geladen() {
        String p = System.getProperty("loader.path");
        if (p == null || p.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(p.split(",")).map(s -> Path.of(s.strip()).getFileName().toString()).toList();
    }

    private static String dateiname(String eintrag) {
        String ohne = eintrag.replace(" (Bibliothek)", "");
        int i = ohne.lastIndexOf(':');
        return ohne.substring(0, i) + "-" + ohne.substring(i + 1) + ".jar";
    }

    @SuppressWarnings("unchecked")
    private static List<String> liste(Object o) {
        return o instanceof List<?> l ? ((List<Object>) l).stream().map(String::valueOf).toList() : List.of();
    }
}

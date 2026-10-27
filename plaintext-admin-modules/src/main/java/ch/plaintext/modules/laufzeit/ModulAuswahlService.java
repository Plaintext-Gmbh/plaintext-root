/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules.laufzeit;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.audit.DestructiveActionAuditService;
import ch.plaintext.lader.ModulAuswahl;
import ch.plaintext.lader.ModulLader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Karte 1608: Seite «Laufzeit-Module» (Auswahl aus signierten Registries, wirksam per «Neu starten»).
 *
 * <ul>
 *   <li>Lesen: ADMIN und ROOT. Schalten (Auswahl speichern, Neu starten): <b>nur ROOT</b>, hier im Dienst geprueft.</li>
 *   <li>Schreibt nur {@code auswahl.yaml} im Modulverzeichnis (Pfad aus der Umgebung, Dateiname fest), atomar mit
 *       {@code .vorher}; was der Lader-Parser ablehnt, wird nicht geschrieben. Registries und Schluessel stehen in
 *       der Instanz-Konfig und sind hier nicht aenderbar.</li>
 *   <li>Neu starten: Audit, dann beendet sich die App mit {@link #EXIT_NEUSTART}; die Restart-Policy des Containers
 *       startet neu und der ModulLader liest die Auswahl. Kein Docker-Socket.</li>
 * </ul>
 */
@Slf4j
@Service
public class ModulAuswahlService {

    /** Eigener Exit-Code fuer den gewollten Neustart (EX_TEMPFAIL), im Log von einem Absturz unterscheidbar. */
    public static final int EXIT_NEUSTART = 75;
    static final String KANAL = "UI";

    /** Eine Version eines Moduls im Angebot. */
    public record Version(String version, String registry, boolean signiert, String passt) implements Serializable {
    }

    /** Ein waehlbares Modul mit allen Versionen aus allen Registries (neueste zuerst). */
    public record Modul(String name, List<Version> versionen, String gewaehlt, String geladen) implements Serializable {
    }

    /** Was die Seite zeigt. */
    public record Ansicht(Map<String, String> status, List<Modul> module, Map<String, String> registryFehler,
                          String quellenFehler, List<String> geladen, String huelle, boolean darfSchalten,
                          String auswahlFehler) implements Serializable {
    }

    private final PlaintextSecurity security;
    private final DestructiveActionAuditService audit;
    private final ApplicationContext kontext;
    private final Path modulVerzeichnis;
    private final Path konfigVerzeichnis;

    public ModulAuswahlService(PlaintextSecurity security, DestructiveActionAuditService audit, ApplicationContext kontext,
                               @Value("${" + ModulLader.VERZEICHNIS_ENV + ":" + ModulLader.STANDARD_VERZEICHNIS + "}")
                               String modulVerzeichnis,
                               @Value("${" + ModulAuswahl.KONFIG_ENV + ":" + ModulAuswahl.STANDARD_KONFIG + "}")
                               String konfigVerzeichnis) {
        this.security = security;
        this.audit = audit;
        this.kontext = kontext;
        this.modulVerzeichnis = Path.of(modulVerzeichnis);
        this.konfigVerzeichnis = Path.of(konfigVerzeichnis);
    }

    public boolean darfSchalten() {
        return security.ifGranted("ROLE_ROOT");
    }

    // ------------------------------------------------------------------------------------------- lesen

    public Ansicht ansicht() throws IOException {
        if (!darfSchalten() && !security.ifGranted("ROLE_ADMIN")) {
            throw new SecurityException("Laufzeit-Module: nur ADMIN und ROOT");
        }
        ModulLader.Huelle huelle = huelle();
        String quellenFehler = null;
        ModulAuswahl.Angebot angebot = new ModulAuswahl.Angebot(List.of(), Map.of());
        List<java.security.PublicKey> schluessel = List.of();
        try {
            ModulAuswahl.Quellen q = ModulAuswahl.quellenLesen(konfigVerzeichnis.resolve(ModulAuswahl.QUELLEN));
            schluessel = q.schluessel();
            if (q.registries().isEmpty()) {
                quellenFehler = "keine Registry in " + konfigVerzeichnis.resolve(ModulAuswahl.QUELLEN);
            }
            angebot = ModulAuswahl.angebot(konfigVerzeichnis);
        } catch (RuntimeException ex) {
            quellenFehler = ex.getMessage();
        }
        String auswahlFehler = null;
        Map<String, String> gewaehlt = new LinkedHashMap<>();
        try {
            auswahl().forEach(w -> gewaehlt.put(w.name(), w.version()));
        } catch (RuntimeException ex) {
            auswahlFehler = ex.getMessage();
        }
        List<String> geladen = LaufzeitModuleService.geladen();
        Map<String, List<Version>> jeName = new LinkedHashMap<>();
        for (ModulAuswahl.Zeile z : angebot.zeilen()) {
            if (z.istModul()) {
                jeName.computeIfAbsent(z.name(), n -> new ArrayList<>()).add(new Version(z.version(), z.registry(),
                        ModulAuswahl.signaturGueltig(z, schluessel),
                        huelle == null ? null : ModulAuswahl.rootMinPasst(z.rootMin(), huelle)));
            }
        }
        gewaehlt.keySet().forEach(n -> jeName.computeIfAbsent(n, x -> new ArrayList<>()));
        List<Modul> module = new ArrayList<>();
        jeName.forEach((n, v) -> {
            v.sort(Comparator.comparing(Version::version, ModulAuswahl::versionVergleich).reversed());
            String im = geladen.stream().filter(g -> g.startsWith(n + "-")).map(g -> g.substring(n.length() + 1, g.length() - 4))
                    .findFirst().orElse(null);
            module.add(new Modul(n, List.copyOf(v), gewaehlt.get(n), im));
        });
        module.sort(Comparator.comparing(Modul::name));
        return new Ansicht(ModulAuswahl.statusLesen(modulVerzeichnis), List.copyOf(module), angebot.fehler(),
                quellenFehler, geladen, huelle == null ? null : huelle.version(), darfSchalten(), auswahlFehler);
    }

    List<ModulAuswahl.Wahl> auswahl() throws IOException {
        Path p = modulVerzeichnis.resolve(ModulAuswahl.AUSWAHL);
        return Files.isRegularFile(p)
                ? ModulAuswahl.auswahlParsen(Files.readString(p, StandardCharsets.UTF_8), p.toString()) : List.of();
    }

    // ------------------------------------------------------------------------------------------- schalten

    /** Schreibt die Auswahl; jedes Modul muss im Angebot stehen, die Version dort oder {@code latest}. */
    public synchronized List<ModulAuswahl.Wahl> speichern(List<ModulAuswahl.Wahl> neu) throws IOException {
        nurRoot();
        Map<String, List<String>> angebot = new LinkedHashMap<>();
        ModulAuswahl.angebot(konfigVerzeichnis).zeilen().stream().filter(ModulAuswahl.Zeile::istModul)
                .forEach(z -> angebot.computeIfAbsent(z.name(), n -> new ArrayList<>()).add(z.version()));
        for (ModulAuswahl.Wahl w : neu) {
            List<String> v = angebot.get(w.name());
            if (v == null) {
                throw new IllegalArgumentException("'" + w.name() + "' steht in keiner erreichbaren Registry");
            }
            if (!ModulAuswahl.LATEST.equals(w.version()) && !v.contains(w.version())) {
                throw new IllegalArgumentException("'" + w.name() + "' " + w.version() + " steht in keiner Registry");
            }
        }
        List<ModulAuswahl.Wahl> alt = nachsichtig();
        String text = ModulAuswahl.auswahlText(neu, "geschrieben von der Seite Laufzeit-Module (Karte 1608), "
                + security.getUser());
        Path ziel = modulVerzeichnis.resolve(ModulAuswahl.AUSWAHL);
        Files.createDirectories(modulVerzeichnis);
        if (Files.isRegularFile(ziel)) {
            atomar(modulVerzeichnis.resolve(ModulAuswahl.AUSWAHL_VORHER), Files.readString(ziel, StandardCharsets.UTF_8));
        }
        atomar(ziel, text);
        protokoll("MODUL_AUSWAHL_SPEICHERN", text(neu), "vorher: " + text(alt));
        return neu;
    }

    /** Kontrolliert beenden; die Restart-Policy startet den Container neu. */
    public void neustart() {
        nurRoot();
        protokoll("MODUL_AUSWAHL_NEUSTART", "Neustart mit Exit " + EXIT_NEUSTART, "Auswahl: " + text(nachsichtig()));
        beenden();
    }

    /** Eigener Thread, damit die Antwort noch ausgeliefert wird. */
    void beenden() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            log.warn("Laufzeit-Module: Neustart angefordert, App beendet sich mit Exit {}", EXIT_NEUSTART);
            System.exit(SpringApplication.exit(kontext, () -> EXIT_NEUSTART));
        }, "modul-auswahl-neustart");
        t.setDaemon(false);
        t.start();
    }

    // ------------------------------------------------------------------------------------------- intern

    private void nurRoot() {
        if (!darfSchalten()) {
            throw new SecurityException("Laufzeit-Module schalten: nur ROOT");
        }
    }

    ModulLader.Huelle huelle() {
        try {
            return ModulLader.huelleAusManifest();
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private List<ModulAuswahl.Wahl> nachsichtig() {
        try {
            return auswahl();
        } catch (IOException | RuntimeException ex) {
            return List.of();
        }
    }

    private static String text(List<ModulAuswahl.Wahl> w) {
        return w.isEmpty() ? "(keine)" : String.join(", ", w.stream().map(x -> x.name() + ":" + x.version()).toList());
    }

    private void atomar(Path ziel, String text) throws IOException {
        Path temp = Files.createTempFile(modulVerzeichnis, "." + ziel.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            Files.move(temp, ziel, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void protokoll(String aktion, String neu, String alt) {
        String wer = security.getUser();
        log.warn("{}: {} - {} ({}) in {}", aktion, wer, neu, alt, modulVerzeichnis);
        audit.logDestructiveAction(KANAL, aktion, ModulAuswahl.AUSWAHL, kurz(neu), kurz("Benutzer " + wer + "; " + alt));
    }

    private static String kurz(String s) {
        return s.length() <= 2000 ? s : s.substring(0, 1997) + "...";
    }
}

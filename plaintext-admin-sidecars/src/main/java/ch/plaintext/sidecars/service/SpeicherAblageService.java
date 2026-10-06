/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.service;

import ch.plaintext.modules.ModulApiUmsetzung;

import ch.plaintext.ablagen.DateiAblage;
import ch.plaintext.ablagen.DateiAblagenRegister;
import ch.plaintext.boot.plugins.netz.AusgehendesZiel;
import ch.plaintext.sidecars.ablage.NextcloudAblage;
import ch.plaintext.sidecars.entity.SpeicherAblage;
import ch.plaintext.sidecars.repository.SpeicherAblageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * Speicher-Ablagen (Karte 1406, Daniel 02.10.2026): unter Root → Sidecars eine Nextcloud mit Zugang
 * und Pfad hinterlegen, damit Module wie Draw.io dort Dateien ablegen ({@link DateiAblagenRegister}).
 *
 * <p>Die Adresse läuft durch {@link AusgehendesZiel}: nur öffentliche Hosts oder die in
 * {@code plaintext.ausgehend.erlaubte-hosts} freigegebenen (z. B. eine Nextcloud im LAN). Das
 * App-Passwort liegt verschlüsselt ({@link SidecarCrypto}) und wird nie angezeigt.</p>
 */
@Slf4j
@Service
@ModulApiUmsetzung(beschreibung = "Verwaltet die unter Root → Sidecars eingerichteten Speicher-Ablagen und gibt Modulen eine Ablage nach Namen.",
        seiteneffekte = ModulApiUmsetzung.Seiteneffekte.INTERN,
        hinweise = {"Adressen nur öffentlich oder ausdrücklich freigegeben", "Lässt sich eine Ablage nicht öffnen, liefert ablage() ein leeres Optional und loggt den Grund"},
        beispiele = {"ablage(\"drawio\")"})
public class SpeicherAblageService implements DateiAblagenRegister {

    private final SpeicherAblageRepository repo;
    private final SidecarCrypto crypto;
    private final Set<String> erlaubteHosts;
    private final HttpClient http;

    @org.springframework.beans.factory.annotation.Autowired
    public SpeicherAblageService(SpeicherAblageRepository repo, SidecarCrypto crypto,
                                 @Value("${" + AusgehendesZiel.EIGENSCHAFT_ERLAUBTE_HOSTS + ":}") String erlaubteHosts) {
        this(repo, crypto, erlaubteHosts, NextcloudAblage.standardClient());
    }

    SpeicherAblageService(SpeicherAblageRepository repo, SidecarCrypto crypto, String erlaubteHosts, HttpClient http) {
        this.repo = repo;
        this.crypto = crypto;
        this.erlaubteHosts = AusgehendesZiel.allowlist(erlaubteHosts);
        this.http = http;
    }

    /** @return alle Ablagen, nach Name */
    public List<SpeicherAblage> liste() {
        return repo.findByDeletedFalseOrderByNameAsc();
    }

    /** @throws NoSuchElementException wenn es die Ablage nicht gibt */
    public SpeicherAblage eintrag(String name) {
        return repo.findFirstByNameAndDeletedFalse(name == null ? "" : name.trim())
                .orElseThrow(() -> new NoSuchElementException("Ablage «" + name + "» ist nicht eingerichtet."));
    }

    /**
     * Legt eine Ablage an oder ändert sie und prüft sie sofort.
     *
     * @param neuesPasswort leer = bisheriges behalten (beim Anlegen Pflicht)
     * @return der gespeicherte Eintrag mit Prüfergebnis
     * @throws IllegalArgumentException bei ungültigen Angaben
     */
    public SpeicherAblage speichere(String name, String url, String benutzer, String neuesPasswort, String pfad) {
        String n = name == null ? "" : name.strip();
        URI u = pruefeAngaben(n, url, benutzer, pfad);
        SpeicherAblage a = repo.findFirstByNameAndDeletedFalse(n).orElseGet(SpeicherAblage::new);
        boolean neu = a.getId() == null;
        if (neu && (neuesPasswort == null || neuesPasswort.isBlank())) {
            throw new IllegalArgumentException("Bitte das App-Passwort angeben.");
        }
        a.setName(n);
        a.setMandat(ch.plaintext.sidecars.entity.Sidecar.MANDAT);
        a.setUrl(u.toString());
        a.setBenutzer(benutzer.strip());
        a.setPfad(pfad.strip());
        if (neuesPasswort != null && !neuesPasswort.isBlank()) {
            a.setPasswortEncrypted(crypto.encrypt(neuesPasswort.strip()));
        }
        try {
            new NextcloudAblage(n, a.getUrl(), a.getBenutzer(), null, a.getPfad(), http);
        } catch (IOException e) {
            throw new IllegalArgumentException("Pfad: " + e.getMessage());
        }
        log.info("Speicher-Ablage «{}» {}: {} als {}, Pfad {}", n, neu ? "angelegt" : "geändert", a.getUrl(), a.getBenutzer(), a.getPfad());
        return pruefe(repo.save(a));
    }

    /**
     * Name, Adresse (SSRF-Pruefung ueber AusgehendesZiel, https ausser freigegebenen Hosts), Benutzer und Pfad,
     * in dieser Reihenfolge (Karte 1416, Sonar java:S3776: aus speichere() herausgeloest).
     */
    private URI pruefeAngaben(String n, String url, String benutzer, String pfad) {
        if (!SidecarBeschreibung.NAME_MUSTER.matcher(n).matches()) {
            throw new IllegalArgumentException("Name: a-z, 0-9 und -, höchstens 40 Zeichen.");
        }
        URI u;
        try {
            u = AusgehendesZiel.pruefeUrl(url, erlaubteHosts);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Adresse: " + e.getMessage());
        }
        if (!"https".equals(u.getScheme()) && !erlaubteHosts.contains(u.getHost().toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("Adresse: nur https, ausser für freigegebene interne Hosts.");
        }
        if (benutzer == null || benutzer.isBlank()) {
            throw new IllegalArgumentException("Bitte den Benutzer angeben.");
        }
        if (pfad == null || pfad.isBlank()) {
            throw new IllegalArgumentException("Bitte den Pfad angeben (Ordner in der Nextcloud).");
        }
        return u;
    }

    /** Prüft Anmeldung und Ordner und speichert das Ergebnis. */
    public SpeicherAblage pruefe(SpeicherAblage a) {
        a.setLetztePruefung(Instant.now());
        try {
            a.setMeldung(oeffne(a).pruefe());
            a.setOk(true);
        } catch (IOException | RuntimeException e) {
            a.setOk(false);
            a.setMeldung(e.getMessage() == null ? e.getClass().getSimpleName() : kurz(e.getMessage()));
        }
        return repo.save(a);
    }

    public void entferne(String name) {
        SpeicherAblage a = eintrag(name);
        a.setDeleted(true);
        repo.save(a);
        log.info("Speicher-Ablage «{}» entfernt", name);
    }

    NextcloudAblage oeffne(SpeicherAblage a) throws IOException {
        // Bei jedem Öffnen erneut prüfen: ein Host kann seit dem Speichern auf eine interne Adresse zeigen.
        try {
            AusgehendesZiel.pruefeUrl(a.getUrl(), erlaubteHosts);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage());
        }
        String pw = a.hatPasswort() ? crypto.decrypt(a.getPasswortEncrypted()) : null;
        return new NextcloudAblage(a.getName(), a.getUrl(), a.getBenutzer(), pw, a.getPfad(), http);
    }

    // ---------- DateiAblagenRegister ----------

    @Override
    public List<String> namen() {
        return liste().stream().map(SpeicherAblage::getName).toList();
    }

    @Override
    public Optional<DateiAblage> ablage(String name) {
        return repo.findFirstByNameAndDeletedFalse(name == null ? "" : name.trim()).flatMap(a -> {
            try {
                return Optional.of(oeffne(a));
            } catch (IOException | RuntimeException e) {
                log.warn("Speicher-Ablage «{}» nicht nutzbar: {}", name, e.getMessage());
                return Optional.empty();
            }
        });
    }

    private static String kurz(String s) {
        return s.length() > 500 ? s.substring(0, 499) + "…" : s;
    }
}

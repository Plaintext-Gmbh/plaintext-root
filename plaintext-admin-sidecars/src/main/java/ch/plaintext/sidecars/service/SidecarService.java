/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.service;

import ch.plaintext.modules.ModulApiUmsetzung;

import ch.plaintext.sidecars.SidecarRegister;
import ch.plaintext.sidecars.SidecarStand;
import ch.plaintext.sidecars.SidecarVerbindung;
import ch.plaintext.sidecars.entity.AuthZustand;
import ch.plaintext.sidecars.entity.Sidecar;
import ch.plaintext.sidecars.entity.SidecarQuelle;
import ch.plaintext.sidecars.repository.SidecarRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Die Sidecar-Registry (Karte 1400, Protokoll {@code docs/SIDECAR_PROTOKOLL.md}).
 *
 * <p>Kennt die Sidecars aus {@code plaintext.sidecars} ({@code name=url}, kommagetrennt) und die von
 * Hand ergänzten, fragt sie nach dem Protokoll ab und hält den letzten Stand in der Tabelle
 * {@code sidecar}. Andere Module fragen über {@link SidecarRegister} nach einer Fähigkeit.</p>
 *
 * <p><b>Welche Adressen erlaubt sind.</b> Die Übersicht ruft jede eingetragene URL vom Server aus
 * auf. Von Hand ergänzt werden deshalb nur Hosts, die schon in der Konfiguration stehen oder in
 * {@code plaintext.sidecars.erlaubte-hosts} freigegeben sind (exakt oder {@code *.suffix}); sonst
 * wäre die Seite ein Werkzeug, um beliebige Adressen im LAN abzufragen.</p>
 */
@Slf4j
@Service
@ModulApiUmsetzung(beschreibung = "Kennt die Sidecars aus der Konfiguration und die von Hand ergänzten, fragt sie nach dem Sidecar-Protokoll ab und vermittelt eine Fähigkeit an den passenden, erreichbaren Sidecar.",
        seiteneffekte = ModulApiUmsetzung.Seiteneffekte.AUSSEN,
        hinweise = {"Nur freigegebene Hosts werden abgefragt", "Bei mehreren Anbietern gewinnt der mit Status ok und kürzester Antwortzeit"},
        beispiele = {"fuer(\"bild.vorschau\")"})
public class SidecarService implements SidecarRegister {

    /** Seltener als das nicht neu abfragen (Seitenaufruf). */
    static final long FRISCH_SEKUNDEN = 60;

    private final SidecarRepository repo;
    private final SidecarProtokollClient client;
    private final SidecarCrypto crypto;
    private final Map<String, String> konfiguriert;
    private final List<String> erlaubteHosts;

    public SidecarService(SidecarRepository repo, SidecarProtokollClient client, SidecarCrypto crypto,
                          @Value("${plaintext.sidecars:}") String sidecars,
                          @Value("${plaintext.sidecars.erlaubte-hosts:}") String erlaubteHosts) {
        this.repo = repo;
        this.client = client;
        this.crypto = crypto;
        this.konfiguriert = leseKonfig(sidecars);
        List<String> hosts = new ArrayList<>();
        for (String h : erlaubteHosts == null ? new String[0] : erlaubteHosts.split(",")) {
            if (!h.isBlank()) {
                hosts.add(h.trim().toLowerCase(Locale.ROOT));
            }
        }
        konfiguriert.values().forEach(u -> hosts.add(URI.create(u).getHost().toLowerCase(Locale.ROOT)));
        this.erlaubteHosts = List.copyOf(hosts);
    }

    /** {@code name=url,name=url} → geordnete Abbildung; ungültige Einträge werden mit Warnung übergangen. */
    static Map<String, String> leseKonfig(String sidecars) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String paar : sidecars == null ? new String[0] : sidecars.split(",")) {
            if (!paar.isBlank()) {
                eintragen(m, paar);
            }
        }
        return m;
    }

    /** Ein Paar {@code name=url}; ungültig: Warnung, nichts eingetragen (Karte 1416, Sonar java:S135). */
    private static void eintragen(Map<String, String> m, String paar) {
        int i = paar.indexOf('=');
        String name = i > 0 ? paar.substring(0, i).trim() : "";
        String url = i > 0 ? normalisiere(paar.substring(i + 1)) : null;
        if (!SidecarBeschreibung.NAME_MUSTER.matcher(name).matches() || url == null) {
            log.warn("plaintext.sidecars: Eintrag «{}» ist kein name=url, übergangen", paar.trim());
            return;
        }
        m.put(name, url);
    }

    /** @return {@code scheme://host[:port][/pfad]} ohne Schrägstrich am Ende, oder {@code null} */
    static String normalisiere(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI u = URI.create(url.trim());
            if (!("http".equals(u.getScheme()) || "https".equals(u.getScheme())) || u.getHost() == null
                    || u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null) {
                return null;
            }
            String s = u.toString();
            while (s.endsWith("/")) {
                s = s.substring(0, s.length() - 1);
            }
            return s;
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    boolean hostErlaubt(String url) {
        String host = URI.create(url).getHost().toLowerCase(Locale.ROOT);
        return erlaubteHosts.stream().anyMatch(h -> h.startsWith("*.") ? host.endsWith(h.substring(1)) : host.equals(h));
    }

    /** Gleicht die Einträge aus der Konfiguration ab (beim Start). */
    @EventListener(ApplicationReadyEvent.class)
    public void abgleichen() {
        try {
            for (Map.Entry<String, String> e : konfiguriert.entrySet()) {
                Sidecar s = repo.findFirstByNameAndDeletedFalse(e.getKey()).orElseGet(Sidecar::new);
                if (s.getId() == null) {
                    s.setName(e.getKey());
                    s.setMandat(Sidecar.MANDAT);
                }
                s.setUrl(e.getValue());
                s.setQuelle(SidecarQuelle.KONFIG);
                repo.save(s);
            }
            for (Sidecar s : repo.findByDeletedFalseOrderByNameAsc()) {
                if (s.getQuelle() == SidecarQuelle.KONFIG && !konfiguriert.containsKey(s.getName())) {
                    s.setDeleted(true);
                    repo.save(s);
                    log.info("Sidecar «{}» steht nicht mehr in plaintext.sidecars, entfernt", s.getName());
                }
            }
        } catch (RuntimeException e) {
            // Eine fehlende Tabelle oder Datenbank darf den Start der Anwendung nicht verhindern.
            log.warn("Sidecar-Abgleich beim Start gescheitert: {}", e.getMessage());
        }
    }

    /** @return alle Einträge, nach Name */
    public List<Sidecar> liste() {
        return repo.findByDeletedFalseOrderByNameAsc();
    }

    /** @throws NoSuchElementException wenn es den Sidecar nicht gibt */
    public Sidecar sidecar(String name) {
        return repo.findFirstByNameAndDeletedFalse(name == null ? "" : name.trim())
                .orElseThrow(() -> new NoSuchElementException("Sidecar «" + name + "» ist nicht bekannt."));
    }

    /** Fragt alle Sidecars ab (Cron). */
    public void aktualisiereAlle() {
        liste().forEach(this::aktualisiere);
    }

    /** Fragt alle ab, deren letzter Stand älter als {@value #FRISCH_SEKUNDEN} s ist (Seitenaufruf). */
    public void aktualisiereVeraltete() {
        Instant grenze = Instant.now().minusSeconds(FRISCH_SEKUNDEN);
        liste().stream().filter(s -> s.getLetzteAbfrage() == null || s.getLetzteAbfrage().isBefore(grenze)).forEach(this::aktualisiere);
    }

    /** Fragt einen Sidecar ab und speichert den Stand. */
    public Sidecar aktualisiere(Sidecar s) {
        SidecarProtokollClient.Abfrage a = client.frage(s.getUrl(), token(s));
        s.setLetzteAbfrage(Instant.now());
        s.setAntwortMs(a.ms());
        s.setAuthZustand(a.auth());
        if (!a.erreichbar()) {
            s.setErreichbar(false);
            s.setFehler(a.fehler());
            return repo.save(s);
        }
        SidecarBeschreibung b = a.beschreibung();
        s.setErreichbar(true);
        s.setProtokoll(b.protokoll());
        s.setTitel(b.titel());
        s.setVersion(b.version());
        s.setStatus(b.status());
        s.setStatusText(b.statusText());
        s.setBeschreibungJson(a.roh());
        List<String> hinweise = new ArrayList<>(b.hinweise());
        if (!b.name().equals(s.getName())) {
            hinweise.addFirst("Meldet sich als «" + b.name() + "» statt «" + s.getName() + "».");
        }
        s.setFehler(hinweise.isEmpty() ? null : kurz(String.join(" ", hinweise)));
        return repo.save(s);
    }

    /**
     * Ergänzt einen Sidecar von Hand. Der Name kommt aus seiner Beschreibung.
     *
     * @throws IllegalArgumentException bei ungültiger oder nicht erlaubter URL, ohne gültiges
     *                                  Protokoll oder wenn der Name schon vergeben ist
     */
    public Sidecar registriere(String url) {
        String u = normalisiere(url);
        if (u == null) {
            throw new IllegalArgumentException("Bitte eine http- oder https-Adresse ohne Benutzer, Query und Anker angeben.");
        }
        if (!hostErlaubt(u)) {
            throw new IllegalArgumentException("Der Host von " + u + " ist nicht freigegeben. Erlaubt sind die Hosts aus "
                    + "plaintext.sidecars und plaintext.sidecars.erlaubte-hosts.");
        }
        SidecarProtokollClient.Abfrage a = client.frage(u, null);
        if (!a.erreichbar()) {
            throw new IllegalArgumentException(a.fehler());
        }
        String name = a.beschreibung().name();
        repo.findFirstByNameAndDeletedFalse(name).ifPresent(vorhanden -> {
            throw new IllegalArgumentException("Den Sidecar «" + name + "» gibt es schon (" + vorhanden.getUrl() + ").");
        });
        Sidecar s = new Sidecar();
        s.setName(name);
        s.setUrl(u);
        s.setQuelle(SidecarQuelle.HAND);
        s.setMandat(Sidecar.MANDAT);
        log.info("Sidecar «{}» von Hand ergänzt: {}", name, u);
        return aktualisiere(repo.save(s));
    }

    /**
     * Entfernt einen von Hand ergänzten Sidecar.
     *
     * @throws IllegalArgumentException bei einem Eintrag aus der Konfiguration
     */
    public void entferne(String name) {
        Sidecar s = sidecar(name);
        if (s.getQuelle() == SidecarQuelle.KONFIG) {
            throw new IllegalArgumentException("«" + name + "» steht in plaintext.sidecars und lässt sich nur dort entfernen.");
        }
        s.setDeleted(true);
        repo.save(s);
        log.info("Sidecar «{}» entfernt", name);
    }

    /**
     * Hinterlegt den Token (leer = entfernen) und prüft ihn sofort.
     *
     * @return der Zustand nach der Prüfung
     */
    public AuthZustand setzeToken(String name, String token) {
        Sidecar s = sidecar(name);
        String t = token == null ? null : token.strip();
        s.setTokenEncrypted(t == null || t.isEmpty() ? null : crypto.encrypt(t));
        repo.save(s);
        log.info("Sidecar «{}»: Token {}", name, s.hatToken() ? "hinterlegt" : "entfernt");
        return aktualisiere(s).getAuthZustand();
    }

    private String token(Sidecar s) {
        if (!s.hatToken()) {
            return null;
        }
        try {
            return crypto.decrypt(s.getTokenEncrypted());
        } catch (RuntimeException e) {
            log.warn("Token des Sidecars «{}» nicht lesbar (anderer PLAINTEXT_SECRET_KEY?): {}", s.getName(), e.getMessage());
            return null;
        }
    }

    /** @return die gespeicherte Beschreibung oder {@code null} */
    public SidecarBeschreibung beschreibung(Sidecar s) {
        if (s.getBeschreibungJson() == null) {
            return null;
        }
        try {
            return SidecarBeschreibung.lies(s.getBeschreibungJson());
        } catch (SidecarBeschreibung.Ungueltig _) {
            return null;
        }
    }

    // ---------- SidecarRegister ----------

    @Override
    public List<SidecarStand> alle() {
        return liste().stream().map(s -> {
            SidecarBeschreibung b = beschreibung(s);
            return new SidecarStand(s.getName(), s.getTitel(), s.getVersion(), s.isErreichbar(),
                    s.isErreichbar() && s.getStatus() != null ? s.getStatus() : "unbekannt",
                    String.valueOf(s.getAuthZustand()),
                    b == null ? List.of() : b.faehigkeiten().stream().map(SidecarBeschreibung.Faehigkeit::id).toList(),
                    s.getLetzteAbfrage());
        }).toList();
    }

    @Override
    public Optional<SidecarVerbindung> verbindung(String name) {
        return repo.findFirstByNameAndDeletedFalse(name == null ? "" : name.trim())
                .map(s -> new SidecarVerbindung(s.getName(), s.getUrl(), token(s)));
    }

    @Override
    public Optional<SidecarVerbindung> fuer(String faehigkeitId) {
        return anbieter(faehigkeitId).stream().findFirst().map(s -> new SidecarVerbindung(s.getName(), s.getUrl(), token(s)));
    }

    /** @return erreichbare Sidecars mit dieser Fähigkeit, der beste zuerst (Status ok, dann schnellste Antwort) */
    public List<Sidecar> anbieter(String faehigkeitId) {
        return liste().stream()
                .filter(s -> s.isErreichbar() && !"fehler".equals(s.getStatus()))
                .filter(s -> {
                    SidecarBeschreibung b = beschreibung(s);
                    return b != null && b.faehigkeit(faehigkeitId) != null;
                })
                .sorted(Comparator.comparing((Sidecar s) -> !"ok".equals(s.getStatus()))
                        .thenComparing(s -> s.getAntwortMs() == null ? Integer.MAX_VALUE : s.getAntwortMs()))
                .toList();
    }

    private static String kurz(String s) {
        return s.length() > 500 ? s.substring(0, 499) + "…" : s;
    }
}

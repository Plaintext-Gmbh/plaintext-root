/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.files.service;

import ch.plaintext.files.DateiEintrag;
import ch.plaintext.files.DateiSpeicher;
import ch.plaintext.files.config.FilesProperties;
import ch.plaintext.framework.EigeneAdresse;
import ch.plaintext.linktoken.LinkRecht;
import ch.plaintext.linktoken.LinkToken;
import ch.plaintext.linktoken.LinkTokenAntrag;
import ch.plaintext.linktoken.LinkTokenStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Persönliche Dateiablage je Benutzer (Karte 1559, aus dem Inventar {@code FilesService}).
 *
 * <p><b>Ablage.</b> Jede Datei liegt im {@link DateiSpeicher} unter {@code files/<benutzer>/<name>}. Sie lebt
 * {@code plaintext.files.frist} (Vorgabe 21 Tage) nach der letzten Änderung; erneutes Hochladen unter demselben Namen
 * ersetzt sie und zählt die Frist neu. Räumt der Speicher nicht selbst auf, gilt eine abgelaufene Datei hier schon
 * als weg und wird beim nächsten Zugriff (und vom {@link FilesAufraeumen}) gelöscht.</p>
 *
 * <p><b>Freigabe-Links.</b> Über den {@link LinkTokenStore}, Art {@value #ART}, Objekt {@code <benutzer>/<name>}.
 * Ein Link gilt höchstens, solange die Datei existiert; mit der Datei verschwindet er.</p>
 */
@Slf4j
@Service
public class FilesService {

    public static final String ART = "files";
    public static final String PFAD = "/nosec/files/";
    public static final ZoneId ZONE = ZoneId.of("Europe/Zurich");
    public static final int MAX_TAGE = 3650;

    private static final String VORGABE_ADRESSE = "https://app.plaintext.ch";
    /** Ordnername aus der Kennung - nur, was in einem Pfad harmlos ist. */
    private static final Pattern NICHT_ERLAUBT_BENUTZER = Pattern.compile("[^a-z0-9._@-]");

    private final DateiSpeicher speicher;
    private final LinkTokenStore links;
    private final FilesProperties properties;
    private final ObjectProvider<EigeneAdresse> adresse;

    public FilesService(DateiSpeicher speicher, LinkTokenStore links, FilesProperties properties,
                        ObjectProvider<EigeneAdresse> adresse) {
        this.speicher = speicher;
        this.links = links;
        this.properties = properties;
        this.adresse = adresse;
    }

    public Duration frist() {
        return properties.getFrist();
    }

    /** Die Dateien des Benutzers, neueste zuerst. */
    public List<Datei> liste(String benutzer) {
        String ordner = ordner(benutzer);
        List<Datei> ergebnis = new ArrayList<>();
        for (DateiEintrag e : speicher.list(ordner)) {
            if (e.ordner()) {
                continue;
            }
            Datei d = new Datei(e.name(), e.groesse(), e.geaendert(), e.geaendert().plus(frist()));
            if (abgelaufen(d)) {
                entferne(ordnerName(benutzer), e.name());
            } else {
                ergebnis.add(d);
            }
        }
        ergebnis.sort(Comparator.comparing(Datei::geaendert).reversed());
        return ergebnis;
    }

    /** Legt die Datei ab oder ersetzt eine gleichnamige - die Frist beginnt neu. */
    public String hochladen(String benutzer, String dateiname, byte[] inhalt) {
        String name = name(dateiname);
        if (inhalt.length > properties.getMaxBytes()) {
            throw new IllegalArgumentException("Datei grösser als " + properties.getMaxBytes() / (1024 * 1024) + " MB");
        }
        speicher.upload(pfad(benutzer, name), inhalt);
        log.info("[FilesService] hochgeladen | benutzer={} | datei={} | bytes={}", benutzer, name, inhalt.length);
        return name;
    }

    /** @return der Inhalt, {@code null} wenn es die Datei nicht (mehr) gibt */
    public byte[] herunterladen(String benutzer, String dateiname) {
        String name = name(dateiname);
        return gibtEs(ordnerName(benutzer), name) ? speicher.download(pfad(benutzer, name)) : null;
    }

    /** Löscht die Datei und ihre Freigabe-Links. */
    public void loeschen(String benutzer, String dateiname) {
        entferne(ordnerName(benutzer), name(dateiname));
        log.info("[FilesService] gelöscht | benutzer={} | datei={}", benutzer, dateiname);
    }

    /** Die Freigabe-Links des Benutzers. */
    public List<LinkToken> links(String benutzer) {
        return links.liste(ART, null, ordnerName(benutzer));
    }

    /** Die Freigabe-Links einer Datei des Benutzers. */
    public List<LinkToken> links(String benutzer, String dateiname) {
        return links.liste(ART, objekt(ordnerName(benutzer), name(dateiname)), ordnerName(benutzer));
    }

    /** Lese-Link, solange die Datei lebt, ohne Zweck - so legt ihn das MCP-Werkzeug an. */
    public LinkToken linkErstellen(String benutzer, String dateiname) {
        return linkErstellen(benutzer, dateiname, 0, null);
    }

    /**
     * Wie das Draw.io-Formular: {@code tage} Gültigkeit (0 = bis die Datei abläuft), {@code zweck} für die Übersicht.
     *
     * @throws IllegalArgumentException wenn es die Datei nicht gibt oder die Gültigkeit ausserhalb 0..3650 liegt
     */
    public LinkToken linkErstellen(String benutzer, String dateiname, int tage, String zweck) {
        String b = ordnerName(benutzer);
        String name = name(dateiname);
        if (tage < 0 || tage > MAX_TAGE) {
            throw new IllegalArgumentException("Gültigkeit zwischen 0 und " + MAX_TAGE + " Tagen");
        }
        if (!gibtEs(b, name)) {
            throw new IllegalArgumentException("Datei '" + name + "' gibt es nicht");
        }
        LocalDate bis = tage == 0 ? null : LocalDate.now(ZONE).plusDays(tage);
        LinkToken l = links.anlegen(new LinkTokenAntrag(ART, objekt(b, name), LinkRecht.LESEN, b, zweck, bis));
        log.info("[FilesService] Link erstellt | benutzer={} | datei={} | bis={}", b, name, bis);
        return l;
    }

    /** Zieht einen Link zurück - nur einen eigenen. */
    public boolean linkWiderrufen(String benutzer, String id) {
        String b = ordnerName(benutzer);
        boolean eigener = links.liste(ART, null, b).stream().anyMatch(l -> l.id().equals(id));
        return eigener && links.widerrufe(ART, id);
    }

    /** Für den öffentlichen Download: der gültige Link zum Token, falls es ihn und seine Datei gibt. Wirft nie. */
    public Optional<LinkToken> finde(String token) {
        try {
            return links.finde(ART, token).filter(l -> {
                String[] teile = teile(l.objekt());
                return teile != null && gibtEs(teile[0], teile[1]);
            });
        } catch (RuntimeException e) {
            log.warn("[FilesService] Link nicht prüfbar | {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Inhalt zu einem Link; zählt den Aufruf. {@code null}, wenn die Datei abgelaufen oder gelöscht ist. */
    public byte[] inhalt(LinkToken link, String token) {
        String[] teile = teile(link.objekt());
        if (teile == null || !gibtEs(teile[0], teile[1])) {
            return null;
        }
        links.aufgerufen(ART, token);
        return speicher.download("files/" + link.objekt());
    }

    /** Dateiname eines Links. */
    public static String datei(LinkToken link) {
        String[] teile = teile(link.objekt());
        return teile == null ? link.objekt() : teile[1];
    }

    /** Die Adresse zu einem Token; {@code null}, wenn das Token nicht bekannt ist (Store speichert nur den Hash). */
    public String url(String token) {
        if (token == null) {
            return null;
        }
        String basis = properties.getLinkBasis() == null || properties.getLinkBasis().isBlank()
                ? Optional.ofNullable(adresse.getIfAvailable()).map(a -> a.basis(VORGABE_ADRESSE)).orElse(VORGABE_ADRESSE)
                : properties.getLinkBasis().strip();
        return (basis.endsWith("/") ? basis.substring(0, basis.length() - 1) : basis) + PFAD + token;
    }

    /**
     * Löscht abgelaufene Dateien aller Benutzer samt Links - nur, wenn der Speicher nicht selbst aufräumt.
     *
     * @return Anzahl gelöschter Dateien
     */
    public int aufraeumen() {
        if (speicher.raeumtSelbstAuf()) {
            return 0;
        }
        int geloescht = 0;
        for (DateiEintrag benutzer : speicher.list("files")) {
            if (!benutzer.ordner()) {
                continue;
            }
            for (DateiEintrag e : speicher.list("files/" + benutzer.name())) {
                if (!e.ordner() && Instant.now().isAfter(e.geaendert().plus(frist()))) {
                    entferne(benutzer.name(), e.name());
                    geloescht++;
                }
            }
        }
        return geloescht;
    }

    private boolean abgelaufen(Datei d) {
        return !speicher.raeumtSelbstAuf() && Instant.now().isAfter(d.ablauf());
    }

    /** Gibt es die Datei und lebt sie noch? */
    private boolean gibtEs(String ordnerName, String name) {
        Optional<DateiEintrag> e = speicher.list("files/" + ordnerName).stream()
                .filter(x -> !x.ordner() && x.name().equals(name)).findFirst();
        if (e.isEmpty()) {
            return false;
        }
        if (!speicher.raeumtSelbstAuf() && Instant.now().isAfter(e.get().geaendert().plus(frist()))) {
            entferne(ordnerName, name);
            return false;
        }
        return true;
    }

    private void entferne(String ordnerName, String name) {
        speicher.delete("files/" + ordnerName + "/" + name);
        links.widerrufeFuer(ART, objekt(ordnerName, name));
    }

    static String ordner(String benutzer) {
        return "files/" + ordnerName(benutzer);
    }

    static String pfad(String benutzer, String name) {
        return ordner(benutzer) + "/" + name;
    }

    private static String objekt(String ordnerName, String name) {
        return ordnerName + "/" + name;
    }

    /** {@code benutzer/name} → beide Teile, {@code null} bei einem kaputten Schlüssel. */
    private static String[] teile(String objekt) {
        int i = objekt == null ? -1 : objekt.indexOf('/');
        return i <= 0 || i == objekt.length() - 1 ? null : new String[]{objekt.substring(0, i), objekt.substring(i + 1)};
    }

    /** Kennung → Ordnername: klein, nur harmlose Zeichen. Leer gibt es nicht. */
    static String ordnerName(String benutzer) {
        String b = benutzer == null ? "" : NICHT_ERLAUBT_BENUTZER.matcher(benutzer.strip().toLowerCase(Locale.ROOT)).replaceAll("_");
        if (b.isEmpty() || b.chars().allMatch(c -> c == '.')) {
            throw new IllegalArgumentException("Kein Benutzer angemeldet");
        }
        return b;
    }

    /** Hochgeladener Name → Dateiname: ohne Pfad, ohne Steuerzeichen, nicht nur Punkte. */
    static String name(String dateiname) {
        String n = dateiname == null ? "" : dateiname.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        if (n.isEmpty() || n.chars().allMatch(c -> c == '.') || n.contains(".upload-")) {
            throw new IllegalArgumentException("Ungültiger Dateiname: " + dateiname);
        }
        return n.length() > 200 ? n.substring(0, 200) : n;
    }
}

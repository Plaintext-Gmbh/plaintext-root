/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.ablage;

import ch.plaintext.PlaintextRoles;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.ablagen.AblageEintrag;
import ch.plaintext.ablagen.DateiAblage;
import ch.plaintext.ablagen.DateiAblagenRegister;
import ch.plaintext.boot.plugins.jsf.FacesMessages;
import lombok.Getter;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.DefaultStreamedContent;
import org.primefaces.model.StreamedContent;
import org.primefaces.model.file.UploadedFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Die Dateiablage als Oberfläche (Karte 1440): Ablage wählen, Ordner durchsuchen, Datei öffnen,
 * herunterladen, hochladen und speichern, über die in root eingerichteten Speicher-Ablagen
 * ({@link DateiAblagenRegister}).
 *
 * <p><b>Wie {@code TableSettings} kein Spring-Bean:</b> die Backing-Bean des Einsatzorts hält ein
 * Exemplar mit ihrem {@link AblageEinsatz}, reicht ihm Register und Security über {@link #init} und
 * gibt es dem Tag {@code pt:dateiAblage} weiter.</p>
 *
 * <pre>{@code
 * @Autowired private transient DateiAblagenRegister register;   // optional, wenn plaintext-admin-sidecars fehlen kann
 * @Autowired private transient PlaintextSecurity security;
 * @Getter private AblageAuswahl ablage;
 *
 * @PostConstruct void init() {
 *     ablage = new AblageAuswahl(new AblageEinsatz(
 *             List.of(new AblageEinsatz.Freigabe("nextcloud-drawio", Set.of("drawio", "ADMIN"), Set.of("ADMIN"))),
 *             "drawio/" + mandatsOrdner, Set.of("drawio", "xml"), 5 * 1024 * 1024));
 *     ablage.init(register, security);
 * }
 * }</pre>
 *
 * <p><b>Alle Prüfungen stehen hier, nicht im Tag.</b> Das Tag blendet nur aus; jeder Zugriff prüft
 * erneut Recht (bei jedem Aufruf, ein entzogenes Recht wirkt sofort), Name, Dateityp und Grösse.
 * Namen sind einzelne Segmente ohne {@code /}, {@code \}, Steuerzeichen, {@code .} und {@code ..};
 * jeder Pfad entsteht als Wurzel + Ordner + Name, ein Ausbrechen aus der Wurzel ist damit nicht
 * möglich. Heruntergeladen wird immer als {@code attachment} mit {@code application/octet-stream},
 * nie inline (eine HTML-Datei aus der Ablage läuft so nicht im Ursprung der Anwendung).</p>
 */
public class AblageAuswahl implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final Pattern VERBOTEN = Pattern.compile("[/\\\\\\p{Cntrl}]");

    private final AblageEinsatz einsatz;
    private transient DateiAblagenRegister register;
    private transient PlaintextSecurity security;

    /** Gewählte Ablage oder {@code null}. */
    @Getter
    private String ablage;
    /** Aktueller Ordner relativ zur Wurzel des Einsatzes, leer = Wurzel. */
    @Getter
    private String ordner = "";
    @Getter
    private List<Eintrag> eintraege = List.of();
    /** Zuletzt geöffnete Datei im aktuellen Ordner und ihr Inhalt. */
    @Getter
    private String gewaehlt;
    @Getter
    private transient byte[] inhalt;
    @Getter
    private transient StreamedContent download;

    /** Ein Eintrag im aktuellen Ordner (in EL mit Methodensyntax lesen: {@code e.name()}). */
    public record Eintrag(String name, boolean ordner, long groesse) implements Serializable {
    }

    public AblageAuswahl(AblageEinsatz einsatz) {
        this.einsatz = einsatz;
    }

    /**
     * @param register darf {@code null} sein (Anwendung ohne Speicher-Ablagen), dann gibt es keine Auswahl
     */
    public void init(DateiAblagenRegister register, PlaintextSecurity security) {
        this.register = register;
        this.security = security;
        if (ablage == null) {
            getAblagen().stream().findFirst().ifPresent(this::setAblage);
        }
    }

    // ---------- Rechte ----------

    /** @return eingerichtete Ablagen, die der Benutzer an diesem Einsatzort lesen darf */
    public List<String> getAblagen() {
        return register == null ? List.of() : register.namen().stream().filter(this::darfLesen).toList();
    }

    public boolean darfLesen(String name) {
        return freigabe(name).map(f -> hat(f.lesen()) || hat(f.schreiben())).orElse(false);
    }

    public boolean darfSchreiben(String name) {
        return freigabe(name).map(f -> hat(f.schreiben())).orElse(false);
    }

    /** @return {@code true}, wenn in der gewählten Ablage geschrieben werden darf (für das Tag) */
    public boolean isSchreibbar() {
        return ablage != null && darfSchreiben(ablage);
    }

    private Optional<AblageEinsatz.Freigabe> freigabe(String name) {
        List<AblageEinsatz.Freigabe> f = einsatz.freigaben();
        return f.stream().filter(x -> x.ablage().equals(name)).findFirst()
                .or(() -> f.stream().filter(x -> AblageEinsatz.ALLE.equals(x.ablage())).findFirst());
    }

    private boolean hat(Set<String> rollen) {
        return PlaintextRoles.hasAny(security, rollen.toArray(String[]::new));
    }

    private DateiAblage zugriff(boolean schreiben) {
        if (ablage == null) {
            throw new IllegalStateException("Keine Ablage gewählt.");
        }
        if (!(schreiben ? darfSchreiben(ablage) : darfLesen(ablage))) {
            throw new SecurityException("Kein " + (schreiben ? "Schreib" : "Lese") + "recht auf die Ablage «" + ablage + "».");
        }
        if (register == null) {
            throw new IllegalStateException("Speicher-Ablagen gibt es in dieser Anwendung nicht.");
        }
        return register.ablage(ablage).orElseThrow(() -> new IllegalStateException("Die Ablage «" + ablage
                + "» ist nicht (mehr) eingerichtet."));
    }

    // ---------- Navigation ----------

    /** Wechselt die Ablage; eine nicht erlaubte bleibt ungewählt. */
    public void setAblage(String name) {
        ablage = null;
        ordner = "";
        eintraege = List.of();
        vergiss();
        if (name == null || name.isBlank()) {
            return;
        }
        if (!getAblagen().contains(name)) {
            FacesMessages.error("Ablage «" + name + "» ist hier nicht verfügbar.");
            return;
        }
        ablage = name;
        laden();
    }

    public void oeffneOrdner(String name) {
        try {
            ordner = verbinde(ordner, pruefeName(name));
            vergiss();
            laden();
        } catch (IllegalArgumentException e) {
            FacesMessages.error(e.getMessage());
        }
    }

    public void hoch() {
        int i = ordner.lastIndexOf('/');
        ordner = i < 0 ? "" : ordner.substring(0, i);
        vergiss();
        laden();
    }

    public boolean isObersterOrdner() {
        return ordner.isEmpty();
    }

    /** Liest den aktuellen Ordner neu: Unterordner und Dateien erlaubter Typen. */
    public void laden() {
        eintraege = List.of();
        if (ablage == null) {
            return;
        }
        try {
            List<AblageEintrag> roh = zugriff(false).liste(voll(""));
            eintraege = roh.stream()
                    .map(e -> new Eintrag(e.pfad().substring(e.pfad().lastIndexOf('/') + 1), e.ordner(), e.groesse()))
                    .filter(e -> gueltig(e.name()) && (e.ordner() || typErlaubt(e.name())))
                    .sorted(Comparator.comparing((Eintrag e) -> !e.ordner()).thenComparing(e -> e.name().toLowerCase(Locale.ROOT)))
                    .toList();
        } catch (IOException | RuntimeException e) {
            FacesMessages.error("Ordner nicht lesbar", e.getMessage());
        }
    }

    // ---------- Dateien (für Module und das Tag) ----------

    /**
     * @param name Dateiname im aktuellen Ordner
     * @throws SecurityException        ohne Leserecht
     * @throws IllegalArgumentException bei ungültigem Namen, nicht erlaubtem Typ oder zu grosser Datei
     */
    public byte[] lies(String name) throws IOException {
        DateiAblage a = zugriff(false);
        pruefeDatei(name);
        byte[] b = a.lies(voll(name));
        pruefeGroesse(b.length);
        return b;
    }

    /**
     * Schreibt eine Datei in den aktuellen Ordner; eine bestehende wird überschrieben.
     *
     * @throws SecurityException        ohne Schreibrecht
     * @throws IllegalArgumentException bei ungültigem Namen, nicht erlaubtem Typ oder zu grosser Datei
     */
    public void speichere(String name, byte[] daten) throws IOException {
        DateiAblage a = zugriff(true);
        pruefeDatei(name);
        pruefeGroesse(daten.length);
        a.schreibe(voll(name), daten, null);
        laden();
    }

    /** Öffnet eine Datei für den Einsatzort: danach stehen {@link #getGewaehlt()} und {@link #getInhalt()}. */
    public void oeffne(String name) {
        vergiss();
        try {
            inhalt = lies(name);
            gewaehlt = name;
        } catch (IOException | RuntimeException e) {
            FacesMessages.error("Öffnen nicht möglich", e.getMessage());
        }
    }

    /** Bereitet {@link #getDownload()} für {@code p:fileDownload} vor (Knopf mit {@code ajax="false"}). */
    public void herunterladen(String name) {
        download = null;
        try {
            byte[] b = lies(name);
            download = DefaultStreamedContent.builder()
                    .name(name)
                    .contentType("application/octet-stream")
                    .contentLength((long) b.length)
                    .stream(() -> new ByteArrayInputStream(b))
                    .build();
        } catch (IOException | RuntimeException e) {
            FacesMessages.error("Herunterladen nicht möglich", e.getMessage());
        }
    }

    /** Listener für {@code p:fileUpload}. */
    public void hochladen(FileUploadEvent event) {
        UploadedFile f = event.getFile();
        try {
            pruefeGroesse(f.getSize());
            speichere(f.getFileName(), f.getContent());
            FacesMessages.info("«" + f.getFileName() + "» gespeichert.");
        } catch (IOException | RuntimeException e) {
            FacesMessages.error("Hochladen nicht möglich", e.getMessage());
        }
    }

    // ---------- Angaben für das Tag ----------

    public long getMaxBytes() {
        return einsatz.maxBytes();
    }

    /** @return Regex für {@code allowTypes} von {@code p:fileUpload} (nur Komfort, geprüft wird hier) */
    public String getAllowTypes() {
        return einsatz.dateitypen().isEmpty() ? null
                : "/\\.(" + einsatz.dateitypen().stream().sorted().collect(Collectors.joining("|")) + ")$/i";
    }

    /** @return erlaubte Endungen zum Anzeigen, leer = alle */
    public String getDateitypenText() {
        return einsatz.dateitypen().stream().sorted().map(t -> "." + t).collect(Collectors.joining(", "));
    }

    // ---------- Prüfungen ----------

    private void vergiss() {
        gewaehlt = null;
        inhalt = null;
        download = null;
    }

    private void pruefeDatei(String name) {
        pruefeName(name);
        if (!typErlaubt(name)) {
            throw new IllegalArgumentException("Dateityp von «" + name + "» ist hier nicht erlaubt (erlaubt: " + getDateitypenText() + ").");
        }
    }

    private void pruefeGroesse(long bytes) {
        if (bytes > einsatz.maxBytes()) {
            throw new IllegalArgumentException("Die Datei ist grösser als " + einsatz.maxBytes() + " Bytes.");
        }
    }

    boolean typErlaubt(String name) {
        int i = name.lastIndexOf('.');
        return einsatz.dateitypen().isEmpty()
                || (i >= 0 && einsatz.dateitypen().contains(name.substring(i + 1).toLowerCase(Locale.ROOT)));
    }

    private String voll(String name) {
        return verbinde(verbinde(einsatz.wurzel(), ordner), name);
    }

    private static String verbinde(String a, String b) {
        return a.isEmpty() ? b : b.isEmpty() ? a : a + "/" + b;
    }

    private static boolean gueltig(String name) {
        return name != null && !name.isBlank() && !".".equals(name) && !"..".equals(name) && name.length() <= 255
                && name.equals(name.strip()) && !VERBOTEN.matcher(name).find();
    }

    /** @return der Name, wenn er ein einzelnes zulässiges Segment ist */
    static String pruefeName(String name) {
        if (!gueltig(name)) {
            throw new IllegalArgumentException("Ungültiger Name «" + name + "»: ein einzelner Datei- oder Ordnername, "
                    + "ohne / \\ . .. und Steuerzeichen.");
        }
        return name;
    }

    /** @return der Pfad, wenn er leer ist oder nur aus zulässigen Segmenten besteht */
    static String pruefePfad(String pfad) {
        if (!pfad.isEmpty()) {
            for (String teil : pfad.split("/", -1)) {
                pruefeName(teil);
            }
        }
        return pfad;
    }
}

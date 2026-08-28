/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.boot.plugins.security.mcp;

import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.model.UserMandate;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.boot.plugins.security.persistence.UserMandateRepository;
import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import ch.plaintext.framework.PrivilegedRoleRules;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * MCP-Werkzeuge fuer die <b>registrierten Benutzer</b> eines Mandanten: auflisten, ausgeben,
 * einlesen.
 *
 * <p><b>Warum es das braucht (Auftrag Daniel, 28.08.2026).</b> Ueber MCP war die Benutzerverwaltung
 * bisher gar nicht erreichbar — von den 139 Werkzeugen der guild-Instanz betraf kein einziges einen
 * Benutzer. Wer Konten von einer Instanz in die andere bringen wollte, musste sie in der Oberflaeche
 * einzeln abtippen. Genau daran haengt der Auszahlungs-Ablauf: Eine Auszahlung gehoert einem
 * Benutzer (Spalte {@code email}), und das Auszahlungsprofil wird ueber dessen Login-Adresse
 * gefunden. Fehlt der Benutzer im Ziel, ist die importierte Auszahlung dort herrenlos.
 *
 * <h2>Was bewusst NICHT ausgegeben wird</h2>
 *
 * <p>Der Export traegt <b>kein Passwort, kein TOTP-Secret, keine Recovery-Codes und kein
 * OIDC-Subject</b>. Das ist keine Bequemlichkeitsluecke, sondern der Kern des Entwurfs: Eine
 * Benutzer-Exportdatei wandert per Datei, Mail oder Zwischenablage: sie ist genau die Art Artefakt,
 * die irgendwann irgendwo liegenbleibt. Ein Passwort-Hash darin waere eine Offline-Angriffsflaeche,
 * ein TOTP-Secret waere der zweite Faktor selbst — der Export wuerde die 2FA aushebeln, die er
 * mitexportiert. Wer Zugangsdaten uebertragen will, hat mit Magic-Link, OIDC und Passwortwechsel
 * drei Wege, die den Benutzer einbeziehen. Diese hier ist keiner davon.
 *
 * <h2>Der Mandant kommt vom Ziel</h2>
 *
 * <p>Wie beim Auszahlungs-Transfer (Karte 936) steht der Quellmandant nur als Herkunftsvermerk im
 * Kopf und wird beim Import <b>ignoriert</b>. Angelegt wird im Mandanten des aufrufenden Tokens.
 * Ein Datensatz, der sein Mandat mitbraechte, legte sonst fremde Benutzer unter falscher Flagge an.
 *
 * <h2>Privilegierte Rollen werden nie importiert</h2>
 *
 * <p>{@code root}, {@code admin} und jede {@code PROPERTY_*}-Rolle werden beim Einlesen
 * <b>verworfen und gezaehlt</b> — unabhaengig davon, wer importiert. Die Begruendung ist dieselbe
 * wie fuer die Allowlist in der Benutzerverwaltung (Karte 307): Eine Importdatei ist Fremdeingabe.
 * Wer aus einer Datei heraus {@code admin} vergeben darf, hat die Rechteausweitung nur um einen
 * Schritt verschoben. Die einzige {@code PROPERTY_*}-Rolle, die ein importierter Benutzer bekommt,
 * ist sein Mandat — und die stammt aus dem <b>Token des Aufrufers</b>, nicht aus der Datei.
 *
 * <h2>Autorisierung</h2>
 *
 * <p>Alle drei Werkzeuge verlangen {@code SCOPE_ADMIN} <b>und</b> die Rolle {@code ADMIN} oder
 * {@code ROOT} — beides zusammen, aus demselben Grund wie in {@code ApiTokenMcpTools}: Der Scope
 * verhindert, dass ein READ-Token schreibt, die Rolle verhindert, dass ein beliebiger Benutzer die
 * Kontenverwaltung uebernimmt. Auch das <em>Lesen</em> steht unter {@code SCOPE_ADMIN}: Die Liste
 * nennt Login-Adressen und Rollen aller Konten eines Mandanten, das ist Kontenverwaltung und keine
 * Alltagsauskunft.
 *
 * <p>{@link ConditionalOnClass} auf die MCP-Annotation: Das Bean laedt nur in Apps mit eigenem
 * spring-ai-MCP-Server (app/guild/schuetu/iot). plaintext-root selbst hat keinen und bleibt
 * unberuehrt.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnClass(name = "org.springaicommunity.mcp.annotation.McpTool")
public class BenutzerMcpTools {

    /** Kennung im Kopf der Datei. Ein Import prueft sie, statt beliebiges JSON zu verdauen. */
    public static final String FORMAT = "plaintext-benutzer";

    /** Formatversion. Steigt, sobald sich die Feldbedeutung aendert — nicht bei blossen Zusaetzen. */
    public static final int VERSION = 1;

    private static final String SCOPE_ADMIN = "SCOPE_ADMIN";
    private static final Set<String> VERWALTER_ROLLEN = Set.of("ROLE_ADMIN", "ROLE_ROOT");

    /**
     * Der Login-Name ist eine E-Mail-Adresse — dieselbe Bedingung, die die Benutzerverwaltung im
     * Formular prueft. Sie steht hier noch einmal, weil ein Import an der Oberflaeche vorbeigeht und
     * ein Konto mit unbrauchbarem Login niemandem nuetzt: Es kann sich nie anmelden, taucht aber in
     * jeder Liste auf.
     */
    private static final Pattern EMAIL = Pattern.compile("^[\\w.%+-]+@[\\w.-]+\\.[A-Za-z]{2,}$");

    private final MyUserRepository userRepository;
    private final UserMandateRepository userMandateRepository;

    /**
     * Eigener Mapper statt der Bean aus {@code JacksonConfig}: Der Export ist ein Dateiformat mit
     * eigenem Vertrag. Haenge er an der global konfigurierten Bean, aenderte eine Umstellung dort
     * still das Format hier.
     */
    private final ObjectMapper json = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            // Unbekannte Felder NICHT als Fehler: sonst scheitert das Lesen schon am Mapping, und der
            // Aufrufer bekaeme „keine lesbare Datei" statt der praezisen Auskunft „falsches Format".
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    // ── Lesen ─────────────────────────────────────────────────────────────────────────────────

    @McpTool(name = "list_benutzer",
            description = "Listet die registrierten Benutzer des aktuellen Mandanten: Login-Adresse, "
            + "Vor-/Nachname, Rollen, ob passwortlos und ob 2FA scharf ist. Enthaelt KEINE Passwoerter, "
            + "TOTP-Secrets oder Recovery-Codes. Erfordert Scope ADMIN sowie die Rolle ADMIN oder ROOT.")
    @PreAuthorize("hasAuthority('SCOPE_ADMIN')")
    public String listBenutzer() {
        String fehler = aufruferPruefen();
        if (fehler != null) {
            return fehler;
        }
        String mandat = mandat();
        List<MyUserEntity> benutzer = benutzerDesMandanten(mandat);
        if (benutzer.isEmpty()) {
            return "Keine Benutzer im Mandanten " + mandat + ".";
        }
        StringBuilder sb = new StringBuilder("Benutzer im Mandanten " + mandat
                + " (" + benutzer.size() + "):\n");
        for (MyUserEntity u : benutzer) {
            sb.append("- ").append(u.getUsername())
                    .append(u.getAnzeigename().isEmpty() ? "" : " (" + u.getAnzeigename() + ")")
                    .append(" rollen=").append(rollenText(u))
                    .append(u.isPasswordless() ? " passwortlos" : "")
                    .append(u.isTotpEnabled() ? " 2FA" : "")
                    .append(zusatzMandateText(u.getUsername(), mandat))
                    .append('\n');
        }
        return sb.toString();
    }

    @McpTool(name = "export_benutzer",
            description = "Gibt die registrierten Benutzer des aktuellen Mandanten als JSON aus — zum "
            + "Einlesen mit import_benutzer in einem anderen Mandanten oder auf einer anderen Instanz. "
            + "Bewusst OHNE Passwort, TOTP-Secret, Recovery-Codes und OIDC-Subject: die Datei ist kein "
            + "Weg, Zugangsdaten zu uebertragen. Erfordert Scope ADMIN sowie die Rolle ADMIN oder ROOT.")
    @PreAuthorize("hasAuthority('SCOPE_ADMIN')")
    public String exportBenutzer() {
        String fehler = aufruferPruefen();
        if (fehler != null) {
            return fehler;
        }
        String mandat = mandat();
        List<MyUserEntity> benutzer = benutzerDesMandanten(mandat);

        Umschlag u = new Umschlag();
        u.setFormat(FORMAT);
        u.setVersion(VERSION);
        u.setExportiertAm(LocalDateTime.now().toString());
        u.setQuellMandat(mandat);           // nur Herkunftsvermerk, beim Import ohne Wirkung
        u.setAnzahl(benutzer.size());
        u.setBenutzer(benutzer.stream().map(BenutzerMcpTools::zuSatz).toList());

        try {
            String text = json.writeValueAsString(u);
            log.info("MCP: export_benutzer mandat={} anzahl={}", mandat, benutzer.size());
            return text;
        } catch (Exception e) {
            log.warn("MCP: export_benutzer fehlgeschlagen: {}", e.getMessage());
            return "FEHLER: Export konnte nicht erzeugt werden: " + e.getMessage();
        }
    }

    // ── Schreiben ─────────────────────────────────────────────────────────────────────────────

    @McpTool(name = "import_benutzer",
            description = "Liest eine Datei aus export_benutzer in den AKTUELLEN Mandanten ein. Der "
            + "Mandant kommt immer vom Ziel, nie aus der Datei. Idempotent ueber die Login-Adresse: "
            + "bekannte Benutzer werden nur in Vor-/Nachname und Startseite ergaenzt, nie in Rollen oder "
            + "Passwort. Neue Benutzer entstehen OHNE Passwort und mit erzwungenem Passwortwechsel, "
            + "koennen sich also erst nach Magic-Link, OIDC oder gesetztem Passwort anmelden. "
            + "Privilegierte Rollen (root, admin, PROPERTY_*) werden verworfen und gezaehlt. "
            + "Erfordert Scope ADMIN sowie die Rolle ADMIN oder ROOT.")
    @PreAuthorize("hasAuthority('SCOPE_ADMIN')")
    @Transactional
    public String importBenutzer(
            @McpToolParam(description = "Der JSON-Text aus export_benutzer") String jsonText) {
        String fehler = aufruferPruefen();
        if (fehler != null) {
            return fehler;
        }
        if (jsonText == null || jsonText.isBlank()) {
            return "FEHLER: jsonText fehlt.";
        }

        Umschlag u;
        try {
            u = json.readValue(jsonText, Umschlag.class);
        } catch (Exception e) {
            return "FEHLER: Das ist keine lesbare Export-Datei: " + e.getMessage();
        }
        if (!FORMAT.equals(u.getFormat())) {
            // Ohne diese Pruefung wuerde ein beliebiges JSON stillschweigend 0 Datensaetze anlegen und
            // als Erfolg gemeldet — der Aufrufer haelt die falsche Datei fuer eingelesen.
            return "FEHLER: Falsches Dateiformat (erwartet: " + FORMAT + ").";
        }
        if (u.getVersion() > VERSION) {
            return "FEHLER: Die Datei stammt aus einer neueren Version (" + u.getVersion()
                    + " > " + VERSION + ").";
        }

        String zielMandat = mandat();
        if (zielMandat == null || zielMandat.isBlank()) {
            return "FEHLER: Kein Mandat im Kontext — Import abgebrochen.";
        }

        Bericht b = new Bericht();
        b.quellMandat = u.getQuellMandat();
        b.zielMandat = zielMandat;
        List<Satz> saetze = u.getBenutzer() == null ? List.of() : u.getBenutzer();
        List<MyUserEntity> zuSpeichern = new ArrayList<>();

        for (Satz s : saetze) {
            b.gelesen++;
            String login = s.getUsername() == null ? "" : s.getUsername().trim().toLowerCase(Locale.ROOT);
            if (!EMAIL.matcher(login).matches()) {
                b.ungueltig++;
                continue;
            }
            MyUserEntity vorhanden = userRepository.findByUsername(login);
            if (vorhanden != null) {
                if (ergaenzeNamen(vorhanden, s)) {
                    zuSpeichern.add(vorhanden);
                    b.aktualisiert++;
                } else {
                    b.unveraendert++;
                }
                continue;
            }
            zuSpeichern.add(neuerBenutzer(login, s, zielMandat, b));
            b.angelegt++;
        }
        userRepository.saveAll(zuSpeichern);
        log.info("MCP: import_benutzer nach {}: {} gelesen, {} angelegt, {} aktualisiert, {} unveraendert,"
                + " {} ungueltig, {} privilegierte Rollen verworfen",
                zielMandat, b.gelesen, b.angelegt, b.aktualisiert, b.unveraendert, b.ungueltig,
                b.verworfeneRollen);
        return b.alsText();
    }

    // ── Abbildung ─────────────────────────────────────────────────────────────────────────────

    private static Satz zuSatz(MyUserEntity u) {
        Satz s = new Satz();
        s.setUsername(u.getUsername());
        s.setVorname(u.getVorname());
        s.setNachname(u.getNachname());
        s.setStartpage(u.getStartpage());
        s.setPasswordless(u.isPasswordless());
        s.setRollen(neutraleRollen(u.getRoles()));
        return s;
    }

    /**
     * Legt ein Konto an, das noch niemandem gehoert: leeres Passwort und erzwungener Wechsel.
     *
     * <p>Warum nicht {@code passwordless=true} als Bequemlichkeit: Passwortlos heisst in dieser
     * Anwendung „darf sich ohne Passwort anmelden" — ein Import wuerde damit fertig nutzbare
     * Zugaenge erzeugen, deren Inhaber von nichts wissen. Leeres Passwort plus
     * {@code mustChangePassword} ist der umgekehrte Fall: Das Konto existiert, ist aber bis zu
     * einer bewussten Handlung (Magic-Link, OIDC, gesetztes Passwort) nicht benutzbar.
     */
    private MyUserEntity neuerBenutzer(String login, Satz s, String zielMandat, Bericht b) {
        MyUserEntity u = new MyUserEntity();
        u.setUsername(login);
        u.setPassword("");
        u.setPasswordless(false);
        u.setMustChangePassword(true);
        u.setVorname(leerZuNull(s.getVorname()));
        u.setNachname(leerZuNull(s.getNachname()));
        u.setStartpage(s.getStartpage() == null ? "" : s.getStartpage());

        Set<String> rollen = new HashSet<>();
        for (String rolle : s.getRollen() == null ? List.<String>of() : s.getRollen()) {
            if (rolle == null || rolle.isBlank()) {
                continue;
            }
            if (PrivilegedRoleRules.isPrivileged(rolle)) {
                b.verworfeneRollen++;
                continue;
            }
            rollen.add(rolle.trim());
        }
        u.setRoles(rollen);
        // Das Mandat stammt aus dem Token des Aufrufers, nicht aus der Datei — siehe Klassen-Javadoc.
        u.setMandat(zielMandat);
        return u;
    }

    /**
     * Ergaenzt bei einem bekannten Konto nur, was dort fehlt.
     *
     * <p>Ein Import darf ein bestehendes Konto nicht ueberschreiben: Rollen, Passwort und 2FA
     * gehoeren dem Ziel, nicht der Datei. Was hier passiert, ist deshalb bewusst wenig — und was
     * nicht passiert, steht im Bericht als „unveraendert".
     *
     * @return {@code true}, wenn sich etwas geaendert hat
     */
    private static boolean ergaenzeNamen(MyUserEntity vorhanden, Satz s) {
        boolean geaendert = false;
        if (istLeer(vorhanden.getVorname()) && !istLeer(s.getVorname())) {
            vorhanden.setVorname(s.getVorname().trim());
            geaendert = true;
        }
        if (istLeer(vorhanden.getNachname()) && !istLeer(s.getNachname())) {
            vorhanden.setNachname(s.getNachname().trim());
            geaendert = true;
        }
        if (istLeer(vorhanden.getStartpage()) && !istLeer(s.getStartpage())) {
            vorhanden.setStartpage(s.getStartpage().trim());
            geaendert = true;
        }
        return geaendert;
    }

    /** Die Rollen ohne die privilegierten — das ist der Umfang, den ein Import ueberhaupt setzen darf. */
    private static List<String> neutraleRollen(Set<String> rollen) {
        if (rollen == null) {
            return List.of();
        }
        return rollen.stream()
                .filter(r -> r != null && !r.isBlank())
                .filter(r -> !PrivilegedRoleRules.isPrivileged(r))
                .sorted()
                .toList();
    }

    // ── Mandant und Aufrufer ──────────────────────────────────────────────────────────────────

    /**
     * Die Benutzer eines Mandanten: die mit ihm als Hauptmandat <b>und</b> die, die ihn als
     * Zusatzmandat zugeteilt bekommen haben.
     *
     * <p>Ohne den zweiten Teil fehlten genau die Konten, die zwischen Mandanten wechseln duerfen —
     * und das sind bei uns die interessanten: Wer nur in einem Mandanten arbeitet, muss nicht
     * transportiert werden.
     */
    private List<MyUserEntity> benutzerDesMandanten(String mandat) {
        if (mandat == null || mandat.isBlank()) {
            return List.of();
        }
        String gesucht = mandat.trim().toLowerCase(Locale.ROOT);
        Set<String> ueberZusatzMandat = userMandateRepository.findByMandatAndActiveTrue(gesucht).stream()
                .map(UserMandate::getUsername)
                .filter(n -> n != null && !n.isBlank())
                .map(n -> n.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        return userRepository.findAll().stream()
                .filter(u -> u.getUsername() != null && !u.getUsername().isBlank())
                .filter(u -> gesucht.equalsIgnoreCase(u.getMandat())
                        || ueberZusatzMandat.contains(u.getUsername().toLowerCase(Locale.ROOT)))
                .sorted((a, c) -> a.getUsername().compareToIgnoreCase(c.getUsername()))
                .toList();
    }

    private String zusatzMandateText(String username, String hauptmandat) {
        Set<String> weitere = new TreeSet<>();
        for (UserMandate um : userMandateRepository.findByUsernameAndActiveTrue(username)) {
            if (um.getMandat() != null && !um.getMandat().equalsIgnoreCase(hauptmandat)) {
                weitere.add(um.getMandat());
            }
        }
        return weitere.isEmpty() ? "" : " weitere_mandate=" + String.join(",", weitere);
    }

    private static String rollenText(MyUserEntity u) {
        List<String> rollen = neutraleRollen(u.getRoles());
        String privilegiert = u.getRoles() == null ? "" : u.getRoles().stream()
                .filter(r -> r != null && PrivilegedRoleRules.isPrivileged(r))
                .filter(r -> !r.toUpperCase(Locale.ROOT).startsWith("PROPERTY_MANDAT_"))
                .sorted()
                .collect(Collectors.joining(","));
        String alle = String.join(",", rollen);
        if (!privilegiert.isEmpty()) {
            alle = alle.isEmpty() ? privilegiert : alle + "," + privilegiert;
        }
        return alle.isEmpty() ? "-" : alle;
    }

    private static String mandat() {
        return PlaintextSecurityHolder.getMandat();
    }

    /**
     * Prueft die Autorisierung des Aufrufers.
     *
     * <p>Die {@code @PreAuthorize}-Annotation deckt den Scope ab, dieser Rumpf die Rolle. Beides
     * einzeln waere zu wenig: Der Scope allein liesse jeden Benutzer mit ADMIN-Token an die
     * Kontenverwaltung, die Rolle allein liesse ein READ-Token schreiben.
     *
     * @return {@code null}, wenn alles stimmt, sonst die fertige Fehlermeldung fuer den Aufrufer
     */
    private static String aufruferPruefen() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "FEHLER: nicht authentisiert.";
        }
        Set<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
        if (!authorities.contains(SCOPE_ADMIN)) {
            return "FEHLER: Benutzerverwaltung erfordert einen Token mit scope=ADMIN.";
        }
        if (VERWALTER_ROLLEN.stream().noneMatch(authorities::contains)) {
            return "FEHLER: Benutzerverwaltung erfordert die Rolle ADMIN oder ROOT.";
        }
        return null;
    }

    private static boolean istLeer(String wert) {
        return wert == null || wert.isBlank();
    }

    private static String leerZuNull(String wert) {
        return istLeer(wert) ? null : wert.trim();
    }

    // ── Datenhalter ───────────────────────────────────────────────────────────────────────────

    /** Kopf der Datei. */
    @Data
    public static class Umschlag {
        private String format;
        private int version;
        private String exportiertAm;
        private String quellMandat;
        private int anzahl;
        private List<Satz> benutzer;
    }

    /**
     * Ein Benutzer — bewusst ohne Id, Mandat und jedes Geheimnis.
     *
     * <p>Die Id des Quellsystems sagt im Ziel nichts; sie zu uebernehmen hiesse, einen fremden
     * Datensatz zu ueberschreiben. Der fachliche Schluessel ist die Login-Adresse.
     */
    @Data
    public static class Satz {
        private String username;
        private String vorname;
        private String nachname;
        private String startpage;
        private boolean passwordless;
        private List<String> rollen;
    }

    /** Was der Import getan hat. */
    @Data
    public static class Bericht {
        private int gelesen;
        private int angelegt;
        private int aktualisiert;
        private int unveraendert;
        private int ungueltig;
        private int verworfeneRollen;
        private String quellMandat;
        private String zielMandat;

        /** Ein Satz fuer den Aufrufer — nennt auch das Verworfene, nicht nur den Erfolg. */
        public String alsText() {
            StringBuilder sb = new StringBuilder();
            sb.append(angelegt).append(" von ").append(gelesen).append(" Benutzern neu angelegt");
            if (aktualisiert > 0) {
                sb.append(", ").append(aktualisiert).append(" ergaenzt");
            }
            if (unveraendert > 0) {
                sb.append(", ").append(unveraendert).append(" unveraendert (schon vorhanden)");
            }
            if (ungueltig > 0) {
                sb.append(", ").append(ungueltig)
                  .append(" uebersprungen (Login ist keine E-Mail-Adresse)");
            }
            if (verworfeneRollen > 0) {
                sb.append(". ").append(verworfeneRollen)
                  .append(" privilegierte Rolle(n) aus der Datei verworfen — root, admin und PROPERTY_* "
                          + "werden nie importiert");
            }
            sb.append(". Neue Konten haben kein Passwort und muessen es beim ersten Login setzen");
            if (quellMandat != null && !quellMandat.equalsIgnoreCase(zielMandat)) {
                sb.append(". Herkunft: ").append(quellMandat)
                  .append(" — angelegt wurde unter ").append(zielMandat);
            }
            return sb.append('.').toString();
        }
    }
}

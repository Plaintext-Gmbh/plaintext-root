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
 * MCP tools for the <b>registered users</b> of a tenant: list, export, import.
 *
 * <p><b>Why this is needed (request from Daniel, 28.08.2026).</b> Via MCP the user administration
 * was not reachable at all so far — of the 139 tools of the guild instance not a single one concerned a
 * user. Whoever wanted to move accounts from one instance to another had to type them into the UI
 * one by one. The payout workflow hangs off exactly this: a payout belongs to a
 * user (column {@code email}), and the payout profile is found via that user's login address.
 * If the user is missing in the target, the imported payout is ownerless there.
 *
 * <h2>What is deliberately NOT exported</h2>
 *
 * <p>The export carries <b>no password, no TOTP secret, no recovery codes and no
 * OIDC subject</b>. That is no convenience gap but the core of the design: a
 * user export file travels by file, mail or clipboard: it is exactly the kind of artifact
 * that ends up lying around somewhere eventually. A password hash in it would be an offline attack
 * surface, a TOTP secret would be the second factor itself — the export would defeat the 2FA that it
 * exports along the way. Whoever wants to transfer credentials has three ways that involve the user:
 * magic link, OIDC and a password change. This one is not among them.
 *
 * <h2>The tenant comes from the target</h2>
 *
 * <p>As with the payout transfer (card 936) the source tenant only stands in the header as a
 * provenance note and is <b>ignored</b> on import. Records are created in the tenant of the calling token.
 * A record that brought its own tenant along would otherwise create foreign users under a false flag.
 *
 * <h2>Privileged roles are never imported</h2>
 *
 * <p>{@code root}, {@code admin} and every {@code PROPERTY_*} role are
 * <b>discarded and counted</b> on import — regardless of who imports. The reasoning is the same
 * as for the allowlist in the user administration (card 307): an import file is foreign input.
 * Whoever may grant {@code admin} out of a file has only moved the privilege escalation one
 * step away. The only {@code PROPERTY_*} role an imported user receives
 * is their tenant — and that one comes from the <b>caller's token</b>, not from the file.
 *
 * <h2>Authorization</h2>
 *
 * <p>All three tools require {@code SCOPE_ADMIN} <b>and</b> the role {@code ADMIN} or
 * {@code ROOT} — both together, for the same reason as in {@code ApiTokenMcpTools}: the scope
 * prevents a READ token from writing, the role prevents an arbitrary user from taking over the
 * account administration. <em>Reading</em>, too, stands under {@code SCOPE_ADMIN}: the list
 * names the login addresses and roles of all accounts of a tenant, that is account administration and no
 * everyday piece of information.
 *
 * <p>{@link ConditionalOnClass} on the MCP annotation: the bean only loads in apps with an MCP
 * server of their own from spring-ai (app/guild/schuetu/iot). plaintext-root itself has none and stays
 * untouched.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnClass(name = "org.springaicommunity.mcp.annotation.McpTool")
public class BenutzerMcpTools {

    /** Identifier in the header of the file. An import checks it instead of digesting arbitrary JSON. */
    public static final String FORMAT = "plaintext-benutzer";

    /** Format version. Increases as soon as the meaning of a field changes — not on mere additions. */
    public static final int VERSION = 1;

    private static final String SCOPE_ADMIN = "SCOPE_ADMIN";
    private static final Set<String> VERWALTER_ROLLEN = Set.of("ROLE_ADMIN", "ROLE_ROOT");

    /**
     * The login name is an e-mail address — the same condition that the user administration checks in
     * the form. It stands here once more, because an import bypasses the UI and
     * an account with an unusable login is of no use to anybody: it can never log in, but shows up in
     * every list.
     */
    private static final Pattern EMAIL = Pattern.compile("^[\\w.%+-]+@[\\w.-]+\\.[A-Za-z]{2,}$");

    private final MyUserRepository userRepository;
    private final UserMandateRepository userMandateRepository;

    /**
     * A mapper of its own instead of the bean from {@code JacksonConfig}: the export is a file format with
     * a contract of its own. If it hung off the globally configured bean, a change there would
     * silently alter the format here.
     */
    private final ObjectMapper json = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            // Unknown fields NOT as an error: otherwise reading would already fail at the mapping, and the
            // caller would get "no readable file" instead of the precise information "wrong format".
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    // ── Reading ───────────────────────────────────────────────────────────────────────────────

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
        u.setQuellMandat(mandat);           // provenance note only, without effect on import
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

    // ── Writing ───────────────────────────────────────────────────────────────────────────────

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
            // Without this check an arbitrary JSON would silently create 0 records and
            // be reported as a success — the caller would think the wrong file had been imported.
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

    // ── Mapping ───────────────────────────────────────────────────────────────────────────────

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
     * Creates an account that does not belong to anybody yet: empty password and a forced change.
     *
     * <p>Why not {@code passwordless=true} as a convenience: passwordless means in this
     * application "may log in without a password" — an import would thereby create ready-to-use
     * accounts whose owners know nothing about them. An empty password plus
     * {@code mustChangePassword} is the opposite case: the account exists, but is not usable until
     * a deliberate action (magic link, OIDC, a password that has been set).
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
        // The tenant comes from the caller's token, not from the file — see the class Javadoc.
        u.setMandat(zielMandat);
        return u;
    }

    /**
     * For a known account, only fills in what is missing there.
     *
     * <p>An import must not overwrite an existing account: roles, password and 2FA
     * belong to the target, not to the file. What happens here is therefore deliberately little — and what
     * does not happen stands in the report as "unchanged".
     *
     * @return {@code true} if something has changed
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

    /** The roles without the privileged ones — that is the scope an import may set at all. */
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

    // ── Tenant and caller ─────────────────────────────────────────────────────────────────────

    /**
     * The users of a tenant: those with it as their main tenant <b>and</b> those who have been
     * assigned it as an additional tenant.
     *
     * <p>Without the second part exactly those accounts would be missing that are allowed to switch
     * between tenants — and here those are the interesting ones: whoever works in only one tenant does
     * not need to be transported.
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
     * Checks the authorization of the caller.
     *
     * <p>The {@code @PreAuthorize} annotation covers the scope, this method body the role. Either one
     * alone would be too little: the scope alone would let every user with an ADMIN token into
     * the account administration, the role alone would let a READ token write.
     *
     * @return {@code null} if everything is in order, otherwise the finished error message for the caller
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

    // ── Data holders ──────────────────────────────────────────────────────────────────────────

    /** Header of the file. */
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
     * A user — deliberately without an id, a tenant and any secret.
     *
     * <p>The id of the source system says nothing in the target; adopting it would mean overwriting a
     * foreign record. The business key is the login address.
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

    /** What the import has done. */
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

        /** One sentence for the caller — it also names what was discarded, not only the success. */
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

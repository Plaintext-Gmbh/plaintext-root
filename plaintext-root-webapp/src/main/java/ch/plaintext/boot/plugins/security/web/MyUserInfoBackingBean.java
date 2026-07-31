/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.plugins.security.PlaintextSecurityProperties;
import ch.plaintext.boot.plugins.security.magiclink.MagicLinkService;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.boot.plugins.security.totp.TotpAuthenticationService;
import ch.plaintext.boot.plugins.security.totp.TotpService;
import ch.plaintext.settings.ISetupConfigService;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import org.springframework.context.annotation.Scope;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
@Data
@Scope("session")
public class MyUserInfoBackingBean implements Serializable {
    private static final long serialVersionUID = 1L;

    // Konstruktor-Injektion statt Field-Injection (Sonar S6813): Lomboks @RequiredArgsConstructor
    // (aus @Data) erzeugt aus den final-Feldern den Injektions-Konstruktor. Alle injizierten
    // Framework-Beans sind transient (Sonar S1948) – sie sind Singletons und nicht Teil des
    // serialisierbaren Session-Zustands dieser Bean.
    private final transient MyUserRepository userRepository;
    private final transient PlaintextSecurity plaintextSecurity;
    private final transient ISetupConfigService setupConfigService;
    private final transient TotpService totpService;
    private final transient TotpAuthenticationService totpAuthenticationService;
    private final transient PlaintextSecurityProperties securityProperties;
    private final transient MagicLinkService magicLinkService;

    /**
     * SECURITY (Karte 314, Punkt 7): zentrale {@link PasswordEncoder}-Bean statt eines lokalen
     * {@code new BCryptPasswordEncoder()}. Der lokale Aufruf haette den Spring-Default-Kostenfaktor
     * 10 behalten, waehrend die Bean in {@code PlaintextSecurityConfig} auf 12 steht — die
     * Kostenfaktoren waeren also je nach Codepfad auseinandergedriftet.
     */
    private final transient PasswordEncoder passwordEncoder;

    // Advanced mode flag (activated via Ctrl+Shift+D)
    private boolean advancedMode = false;

    // Password change fields
    private String currentPassword;
    private String newPassword;
    private String confirmPassword;

    // === TOTP / 2FA self-service state ===
    /** Waehrend der Einrichtung gehaltenes (noch nicht aktiviertes) Secret. */
    private String totpSetupSecret;
    /** Data-URI des QR-Codes fuer die Einrichtung. */
    private String totpQrCodeDataUri;
    /** Vom User eingegebener Bestaetigungscode. */
    private String totpConfirmationCode;
    /** Passwort zur Bestaetigung beim Deaktivieren. */
    private String totpDisablePassword;
    /** Einmalig angezeigte Klartext-Recovery-Codes (nur direkt nach Aktivierung). */
    private List<String> generatedRecoveryCodes = new ArrayList<>();

    public String getUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() != null) {
            if (auth.getPrincipal() instanceof org.springframework.security.core.userdetails.User user) {
                return user.getUsername();
            }
            return auth.getName();
        }
        return "N/A";
    }

    public List<String> getRoles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getAuthorities() != null) {
            return auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(a -> a.startsWith("ROLE_"))
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    public List<String> getProperties() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getAuthorities() != null) {
            return auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(a -> a.startsWith("PROPERTY_"))
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    public String getMandat() {
        return getProperties().stream()
                .filter(p -> p.startsWith("PROPERTY_MANDAT_"))
                .map(p -> p.substring("PROPERTY_MANDAT_".length()))
                .findFirst()
                .orElse("N/A");
    }

    public String getStartpage() {
        return getProperties().stream()
                .filter(p -> p.startsWith("PROPERTY_STARTPAGE_"))
                .map(p -> p.substring("PROPERTY_STARTPAGE_".length()))
                .findFirst()
                .orElse("N/A");
    }

    /**
     * Returns the user's startpage with fallback to index.html.
     * If startpage is null, empty, or "N/A", returns "index.html".
     * Otherwise returns the configured startpage with faces-redirect.
     */
    public String getStartpageOrDefault() {
        String startpage = getStartpage();

        if (startpage == null || startpage.trim().isEmpty() || "N/A".equalsIgnoreCase(startpage)) {
            return "/index.html?faces-redirect=true";
        }

        // Ensure .xhtml or .html extension
        if (!startpage.endsWith(".xhtml") && !startpage.endsWith(".html")) {
            startpage = startpage + ".xhtml";
        }

        // Add leading slash if not present
        if (!startpage.startsWith("/")) {
            startpage = "/" + startpage;
        }

        return startpage + "?faces-redirect=true";
    }

    public String getMyUserId() {
        return getProperties().stream()
                .filter(p -> p.startsWith("PROPERTY_MYUSERID_"))
                .map(p -> p.substring("PROPERTY_MYUSERID_".length()))
                .findFirst()
                .orElse("N/A");
    }

    public boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated();
    }

    public boolean isAccountNonExpired() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof org.springframework.security.core.userdetails.User) {
            return ((org.springframework.security.core.userdetails.User) auth.getPrincipal()).isAccountNonExpired();
        }
        return false;
    }

    public boolean isAccountNonLocked() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof org.springframework.security.core.userdetails.User) {
            return ((org.springframework.security.core.userdetails.User) auth.getPrincipal()).isAccountNonLocked();
        }
        return false;
    }

    public boolean isCredentialsNonExpired() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof org.springframework.security.core.userdetails.User) {
            return ((org.springframework.security.core.userdetails.User) auth.getPrincipal()).isCredentialsNonExpired();
        }
        return false;
    }

    public boolean isEnabled() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof org.springframework.security.core.userdetails.User) {
            return ((org.springframework.security.core.userdetails.User) auth.getPrincipal()).isEnabled();
        }
        return false;
    }

    /**
     * Checks if password management is enabled for the current user's mandat.
     */
    public boolean isPasswordManagementEnabled() {
        return setupConfigService == null || setupConfigService.isPasswordManagementEnabled(getMandat());
    }

    /**
     * Generiert und versendet einen Magic-Link an die E-Mail-Adresse des eingeloggten Users.
     */
    public void sendMagicLinkToSelf() {
        FacesContext context = FacesContext.getCurrentInstance();
        String username = getUsername();
        HttpServletRequest request = (HttpServletRequest) context.getExternalContext().getRequest();
        boolean sent = magicLinkService.generateAndSend(username, request);
        if (sent) {
            context.addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Erfolg",
                            "Anmelde-Link (Magic-Link) an deine E-Mail-Adresse gesendet."));
        } else {
            context.addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "Warnung",
                            "Magic-Link konnte nicht gesendet werden (Feature deaktiviert oder kein System-Mailkonto konfiguriert)."));
        }
    }

    /**
     * Navigates to the user's configured startpage or falls back to index.html.
     * This method is used by JSF navigation from the access-denied page.
     */
    public String navigateToStartpage() {
        if (plaintextSecurity != null) {
            return plaintextSecurity.getStartpageOrDefault();
        }
        return "/index.html?faces-redirect=true";
    }

    /**
     * Toggles the advanced mode (activated via Ctrl+Shift+D).
     * Shows additional fields like Account Status, Weitere Eigenschaften, and Rolle zuweisen.
     */
    public void toggleAdvancedMode() {
        this.advancedMode = !this.advancedMode;

        // Pass the new state to JavaScript via callback parameter
        org.primefaces.PrimeFaces.current().ajax().addCallbackParam("advancedModeEnabled", this.advancedMode);
    }

    /**
     * Changes the password for the current user.
     */
    public void changePassword() {
        FacesContext context = FacesContext.getCurrentInstance();

        // Validate input
        if (currentPassword == null || currentPassword.trim().isEmpty()) {
            context.addMessage("passwordMessages",
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Bitte geben Sie Ihr aktuelles Passwort ein."));
            return;
        }

        if (newPassword == null || newPassword.trim().isEmpty()) {
            context.addMessage("passwordMessages",
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Bitte geben Sie ein neues Passwort ein."));
            return;
        }

        if (confirmPassword == null || confirmPassword.trim().isEmpty()) {
            context.addMessage("passwordMessages",
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Bitte bestätigen Sie das neue Passwort."));
            return;
        }

        if (!newPassword.equals(confirmPassword)) {
            context.addMessage("passwordMessages",
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Die neuen Passwörter stimmen nicht überein."));
            return;
        }

        // Get current user
        String username = getUsername();
        if (username == null || "N/A".equals(username)) {
            context.addMessage("passwordMessages",
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Benutzer konnte nicht ermittelt werden."));
            return;
        }

        MyUserEntity user = userRepository.findByUsername(username);
        if (user == null) {
            context.addMessage("passwordMessages",
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Benutzer nicht gefunden."));
            return;
        }

        // Verify current password
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            context.addMessage("passwordMessages",
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Das aktuelle Passwort ist nicht korrekt."));
            return;
        }

        // Update password
        user.setPassword(passwordEncoder.encode(newPassword));
        // Karte 306: ein evtl. erzwungener Wechsel (Root-Initialpasswort) ist hiermit erledigt.
        user.setMustChangePassword(false);
        userRepository.save(user);

        // Clear fields
        currentPassword = null;
        newPassword = null;
        confirmPassword = null;

        context.addMessage("passwordMessages",
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Passwort wurde erfolgreich geändert."));
    }

    // ===================================================================================
    // TOTP / Zwei-Faktor-Authentifizierung (Self-Service)
    // Nur sichtbar/aktiv, wenn plaintext.security.totp.enabled=true UND der User nicht
    // passwordless (OIDC-only) ist.
    // ===================================================================================

    /** Ob der 2FA-Bereich ueberhaupt angezeigt werden soll (Feature an + lokaler User). */
    public boolean isTotpFeatureAvailable() {
        if (securityProperties == null || !securityProperties.getTotp().isEnabled()) {
            return false;
        }
        MyUserEntity user = currentUserOrNull();
        return user != null && !user.isPasswordless();
    }

    /** Ob der aktuelle User 2FA aktiviert hat. */
    public boolean isTotpEnabled() {
        MyUserEntity user = currentUserOrNull();
        return user != null && user.isTotpEnabled();
    }

    /**
     * Startet die Einrichtung: generiert ein frisches Secret + QR-Code. Aktiviert 2FA
     * noch NICHT (erst nach Code-Bestaetigung), damit sich niemand versehentlich aussperrt.
     */
    public void beginTotpSetup() {
        FacesContext context = FacesContext.getCurrentInstance();
        if (!isTotpFeatureAvailable()) {
            addTotpMessage(context, FacesMessage.SEVERITY_WARN, "Zwei-Faktor-Authentifizierung ist nicht verfügbar.");
            return;
        }
        String username = getUsername();
        this.totpSetupSecret = totpService.generateSecret();
        this.totpQrCodeDataUri = totpService.generateQrCodeDataUri(totpSetupSecret, username);
        this.totpConfirmationCode = null;
        this.generatedRecoveryCodes = new ArrayList<>();
    }

    /** Bricht eine laufende Einrichtung ab (Secret verwerfen). */
    public void cancelTotpSetup() {
        this.totpSetupSecret = null;
        this.totpQrCodeDataUri = null;
        this.totpConfirmationCode = null;
    }

    /**
     * Schliesst die Einrichtung ab: prueft den Bestaetigungscode gegen das gehaltene Secret,
     * aktiviert 2FA und zeigt die Recovery-Codes EINMALIG an.
     */
    public void confirmTotpSetup() {
        FacesContext context = FacesContext.getCurrentInstance();
        if (!isTotpFeatureAvailable() || totpSetupSecret == null) {
            addTotpMessage(context, FacesMessage.SEVERITY_ERROR, "Es läuft keine Einrichtung. Bitte erneut starten.");
            return;
        }
        List<String> codes = totpAuthenticationService.confirmAndEnable(getUsername(), totpSetupSecret, totpConfirmationCode);
        if (codes == null) {
            addTotpMessage(context, FacesMessage.SEVERITY_ERROR, "Der Code ist nicht korrekt. Bitte erneut versuchen.");
            return;
        }
        // Einrichtung erfolgreich abgeschlossen.
        this.generatedRecoveryCodes = codes;
        this.totpSetupSecret = null;
        this.totpQrCodeDataUri = null;
        this.totpConfirmationCode = null;
        addTotpMessage(context, FacesMessage.SEVERITY_INFO,
                "Zwei-Faktor-Authentifizierung aktiviert. Bitte speichern Sie Ihre Recovery-Codes jetzt.");
    }

    /**
     * Deaktiviert 2FA nach Passwort-Bestaetigung. Das Passwort verhindert, dass ein bereits
     * eingeloggter Angreifer (fremde offene Session) den zweiten Faktor still abschaltet.
     */
    public void disableTotp() {
        FacesContext context = FacesContext.getCurrentInstance();
        if (!isTotpFeatureAvailable() || !isTotpEnabled()) {
            addTotpMessage(context, FacesMessage.SEVERITY_WARN, "Zwei-Faktor-Authentifizierung ist nicht aktiv.");
            return;
        }
        MyUserEntity user = currentUserOrNull();
        if (user == null || totpDisablePassword == null
                || !passwordEncoder.matches(totpDisablePassword, user.getPassword())) {
            addTotpMessage(context, FacesMessage.SEVERITY_ERROR, "Das Passwort ist nicht korrekt.");
            return;
        }
        totpAuthenticationService.disable(getUsername());
        this.totpDisablePassword = null;
        this.generatedRecoveryCodes = new ArrayList<>();
        addTotpMessage(context, FacesMessage.SEVERITY_INFO, "Zwei-Faktor-Authentifizierung wurde deaktiviert.");
    }

    private MyUserEntity currentUserOrNull() {
        if (userRepository == null) {
            return null;
        }
        String username = getUsername();
        if (username == null || "N/A".equals(username)) {
            return null;
        }
        return userRepository.findByUsername(username);
    }

    private void addTotpMessage(FacesContext context, FacesMessage.Severity severity, String detail) {
        if (context != null) {
            context.addMessage("totpMessages", new FacesMessage(severity, "2FA", detail));
        }
    }
}

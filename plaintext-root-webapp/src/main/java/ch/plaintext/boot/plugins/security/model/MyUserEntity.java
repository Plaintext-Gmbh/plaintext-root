/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.model;

import ch.plaintext.boot.plugins.security.helpers.MyUserSetConverter;
import ch.plaintext.boot.plugins.security.helpers.RecoveryCodesConverter;
import ch.plaintext.boot.plugins.security.helpers.TotpSecretConverter;
import jakarta.persistence.*;
import lombok.Data;

import java.util.HashSet;
import java.util.Set;

@Entity
@Data
public class MyUserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;
    private String password = "";
    private String startpage = "";

    @Column(name = "OIDC_SUBJECT")
    private String oidcSubject;

    private boolean passwordless;

    /**
     * Forces a password change on the next login (card 306). Is set for the
     * root bootstrap user (one-off initial password instead of a static {@code root}) and
     * cleared again after a successful self-service password change. Default {@code false}, so that
     * existing users are not affected.
     */
    @Column(name = "MUST_CHANGE_PASSWORD")
    private boolean mustChangePassword;

    /**
     * Base32-encoded TOTP secret (RFC 6238). {@code null} as long as 2FA has not been
     * set up. The secret is the shared key between the server and the
     * authenticator app; it is checked exclusively locally and never passed on beyond the
     * login boundary.
     */
    @Column(name = "TOTP_SECRET", length = 255)
    @Convert(converter = TotpSecretConverter.class)
    private String totpSecret;

    /**
     * Whether the second factor is armed for this (local) user. Only {@code true}
     * after the user has confirmed a valid code during setup
     * (no lockout through activating it by accident). Default {@code false}.
     */
    @Column(name = "TOTP_ENABLED")
    private boolean totpEnabled;

    /**
     * Hashed (SHA-256, hex) one-time recovery codes. The plaintext is shown to the user
     * exactly once during setup and afterwards stored only in hashed form –
     * this way a legitimate user can never lock themselves out permanently despite a lost
     * authenticator, without a DB leak disclosing the codes.
     */
    @Convert(converter = RecoveryCodesConverter.class)
    @Column(name = "RECOVERY_CODES", length = 2000)
    private Set<String> recoveryCodes = new HashSet<>();

    @Convert(converter = MyUserSetConverter.class)
    private Set<String> roles = new HashSet<>();

    /**
     * First and last name, both optional.
     *
     * <p><b>Why they are new (request from Daniel, 25.08.2026: "also show the first and last name").</b>
     * On the user they did <b>not</b> exist up to this point — neither as a field nor as a column in
     * {@code my_user_entity}. The framework has always expected them, though:
     * {@code ch.plaintext.framework.PlaintextUser} declares {@code getVorname()} and
     * {@code getNachname()}. Only, that interface had <b>not a single implementation</b>,
     * so the expectation went nowhere.
     *
     * <p>They deliberately remain {@code null}-able: existing accounts have no names, and a
     * mandatory field would block every save of a legacy account.
     */
    private String vorname;

    private String nachname;

    /** First and last name together, or empty — for display and sorting in a single column. */
    @Transient
    public String getAnzeigename() {
        String v = vorname == null ? "" : vorname.trim();
        String n = nachname == null ? "" : nachname.trim();
        return (v + " " + n).trim();
    }

    public void addRole(String role) {
        roles.add(role);
    }

    public void removeRole(String role) {
        roles.remove(role);
    }

    @Transient
    public String getMandat() {
        if (roles == null) {
            return null;
        }
        for (String role : roles) {
            if (role != null && role.toUpperCase().startsWith("PROPERTY_MANDAT_")) {
                return role.substring("PROPERTY_MANDAT_".length()).toLowerCase();
            }
        }
        return null;
    }

    public void setMandat(String mandat) {
        if (roles == null) {
            roles = new HashSet<>();
        }
        // Remove any existing mandat role
        roles.removeIf(role -> role != null && role.toUpperCase().startsWith("PROPERTY_MANDAT_"));

        // Add new mandat role if mandat is not null or empty
        if (mandat != null && !mandat.trim().isEmpty()) {
            roles.add("PROPERTY_MANDAT_" + mandat.toUpperCase());
        }
    }

}

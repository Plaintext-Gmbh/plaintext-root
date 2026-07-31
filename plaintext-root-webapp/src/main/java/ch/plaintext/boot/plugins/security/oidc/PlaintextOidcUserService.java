/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.oidc;

import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.oidc.entity.OidcConfig;
import ch.plaintext.oidc.service.OidcConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class PlaintextOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final MyUserRepository userRepository;
    private final OidcConfigService oidcConfigService;
    private final ch.plaintext.boot.plugins.security.PlaintextSecurityProperties securityProperties;
    private final OidcUserService delegate = new OidcUserService();

    /**
     * SECURITY (Karte 314, Punkt 12): verlangt {@code email_verified=true}, bevor ein bestehendes
     * lokales Konto erstmalig an einen IdP-{@code sub} gebunden wird. Fail-closed: ein fehlender
     * Claim gilt als "nicht verifiziert". Ueber
     * {@code plaintext.security.oidc-require-verified-email=false} abschaltbar, falls ein IdP den
     * Claim nachweislich nicht liefert.
     */
    private void requireVerifiedEmail(OidcUser oidcUser, String username, String usernameAttr) {
        if (!securityProperties.isOidcRequireVerifiedEmail()) {
            return;
        }
        Object claim = oidcUser.getClaim("email_verified");
        boolean verified = (claim instanceof Boolean b && b)
                || (claim instanceof String s && Boolean.parseBoolean(s));
        if (verified) {
            return;
        }
        log.warn("OIDC-Verlinkung abgelehnt: email_verified={} fuer bestehenden Benutzer '{}' "
                        + "(Attribut '{}'). Ein bestehendes Konto wird nur mit verifizierter Adresse "
                        + "an einen IdP-Subject gebunden.",
                claim, username, usernameAttr);
        throw new OAuth2AuthenticationException(
                new OAuth2Error("email_not_verified"),
                "Die E-Mail-Adresse ist beim Identity-Provider nicht als verifiziert markiert. "
                        + "Das bestehende Konto wird deshalb nicht verknuepft.");
    }

    @Override
    @Transactional
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = delegate.loadUser(userRequest);

        OidcConfig config = oidcConfigService.getActiveConfig()
                .orElseThrow(() -> new OAuth2AuthenticationException(
                        new OAuth2Error("oidc_config_missing"), "Keine aktive OIDC-Konfiguration"));

        String oidcSubject = oidcUser.getSubject();
        String usernameAttr = config.getUsernameAttribute() != null ? config.getUsernameAttribute() : "email";
        String username = extractAttribute(oidcUser, usernameAttr);

        if (username == null || username.isBlank()) {
            log.error("OIDC login failed: attribute '{}' is empty for subject {}", usernameAttr, oidcSubject);
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("missing_attribute"),
                    "OIDC-Attribut '" + usernameAttr + "' fehlt");
        }

        log.info("OIDC login attempt: subject={}, username={}", oidcSubject, username);

        // 1. Try to find user by OIDC subject (already linked)
        MyUserEntity localUser = userRepository.findByOidcSubject(oidcSubject);

        // 2. Try to find by username/email (first login migration)
        if (localUser == null) {
            localUser = userRepository.findByUsername(username);
            if (localUser != null) {
                // SECURITY (Karte 314, Punkt 12): Kontouebernahme-Schutz beim Verlinken.
                // Hier wird ein BESTEHENDES lokales Konto allein aufgrund der vom IdP
                // gelieferten Mailadresse an einen IdP-'sub' gebunden. Laesst der IdP
                // unverifizierte Adressen zu, koennte sich jemand dort mit der Adresse eines
                // bestehenden Benutzers registrieren und danach dessen Konto uebernehmen.
                // Beim erstmaligen Verlinken verlangen wir deshalb email_verified=true.
                // Bewusst NUR an dieser Stelle: bereits verlinkte Konten (findByOidcSubject)
                // und Auto-Create sind nicht betroffen, ein IdP ohne den Claim wird nicht
                // stillschweigend akzeptiert, sondern mit klarer Meldung abgelehnt.
                requireVerifiedEmail(oidcUser, username, usernameAttr);
                // Link existing user with OIDC subject
                localUser.setOidcSubject(oidcSubject);
                userRepository.save(localUser);
                log.info("Linked existing user '{}' (id={}) with OIDC subject {}",
                        username, localUser.getId(), oidcSubject);
            }
        }

        // 3. Auto-create if configured
        if (localUser == null) {
            if (!config.isAutoCreateUsers()) {
                log.warn("OIDC login rejected: user '{}' not found and auto-create disabled", username);
                throw new OAuth2AuthenticationException(
                        new OAuth2Error("user_not_found"),
                        "Benutzer '" + username + "' ist nicht registriert");
            }

            localUser = createNewUser(username, oidcSubject, config);
            log.info("Auto-created new user '{}' (id={}) from OIDC login", username, localUser.getId());
        }

        // Build authorities matching local login format
        List<GrantedAuthority> authorities = buildAuthorities(localUser);

        return new DefaultOidcUser(authorities, oidcUser.getIdToken(), oidcUser.getUserInfo(), usernameAttr);
    }

    private String extractAttribute(OidcUser oidcUser, String attributeName) {
        Object value = oidcUser.getAttribute(attributeName);
        if (value != null) {
            return value.toString();
        }
        // Fallback: try claims. getIdToken() ist laut OidcUser-Vertrag @NonNull, daher keine
        // (immer wahre) Null-Pruefung mehr (Sonar S2589).
        value = oidcUser.getIdToken().getClaim(attributeName);
        if (value != null) {
            return value.toString();
        }
        return null;
    }

    private MyUserEntity createNewUser(String username, String oidcSubject, OidcConfig config) {
        MyUserEntity user = new MyUserEntity();
        user.setUsername(username);
        user.setPassword(""); // No local password for OIDC users
        user.setOidcSubject(oidcSubject);

        // Set default roles
        Set<String> roles = new HashSet<>();
        if (config.getDefaultRoles() != null && !config.getDefaultRoles().isBlank()) {
            Arrays.stream(config.getDefaultRoles().split(","))
                    .map(String::trim)
                    .filter(r -> !r.isEmpty())
                    .forEach(roles::add);
        }

        // Set default mandate
        String mandat = config.getDefaultMandat() != null ? config.getDefaultMandat() : "default";
        roles.add("PROPERTY_MANDAT_" + mandat.toUpperCase());

        user.setRoles(roles);
        return userRepository.save(user);
    }

    private List<GrantedAuthority> buildAuthorities(MyUserEntity user) {
        List<GrantedAuthority> authorities = new ArrayList<>();

        // Add user ID
        authorities.add(new SimpleGrantedAuthority("PROPERTY_MYUSERID_" + user.getId()));

        // Add roles (same logic as MyUserDetailsService)
        if (user.getRoles() != null) {
            for (String role : user.getRoles()) {
                if (role.toLowerCase().contains("mandat")) {
                    authorities.add(new SimpleGrantedAuthority(role.toUpperCase()));
                } else {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
                }
            }
        }

        // Add startpage
        if (user.getStartpage() != null && !user.getStartpage().isEmpty()) {
            authorities.add(new SimpleGrantedAuthority("PROPERTY_STARTPAGE_" + user.getStartpage()));
        }

        return authorities;
    }
}

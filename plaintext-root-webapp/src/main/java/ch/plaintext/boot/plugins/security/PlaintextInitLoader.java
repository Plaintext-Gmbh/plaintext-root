/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.settings.ISetupConfigService;
import ch.plaintext.settings.RootUserToggleEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;

@Service
@Slf4j
public class PlaintextInitLoader {

    private static final String ROOT_USERNAME = "root@root.root";

    /** Source of the one-time initial password for the root bootstrap user (card 306). */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final MyUserRepository userRepository;
    private final ISetupConfigService setupConfigService;
    /**
     * SECURITY (card 314, item 7): central {@link PasswordEncoder} bean instead of a local
     * {@code new BCryptPasswordEncoder()}. The local call would have kept Spring's default cost factor
     * 10, while the bean in {@code PlaintextSecurityConfig} stands at 12 — the
     * cost factors would therefore have drifted apart depending on the code path.
     */
    private final PasswordEncoder passwordEncoder;

    public PlaintextInitLoader(MyUserRepository userRepository, ISetupConfigService setupConfigService,
                               PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.setupConfigService = setupConfigService;
        this.passwordEncoder = passwordEncoder;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void createRootUserDelayed() {
        if (!setupConfigService.isRootUserEnabled("default")) {
            log.info("Root user creation disabled via setup config, skipping");
            return;
        }

        MyUserEntity existingRoot = userRepository.findByUsername(ROOT_USERNAME);
        if (existingRoot == null) {
            createRootUser();
        } else {
            log.info("Root user '{}' already exists, skipping creation", ROOT_USERNAME);
        }
    }

    @EventListener
    public void onRootUserToggle(RootUserToggleEvent event) {
        if (event.isEnabled()) {
            MyUserEntity existingRoot = userRepository.findByUsername(ROOT_USERNAME);
            if (existingRoot == null) {
                createRootUser();
            } else {
                log.info("Root user '{}' already exists", ROOT_USERNAME);
            }
        } else {
            MyUserEntity existingRoot = userRepository.findByUsername(ROOT_USERNAME);
            if (existingRoot != null) {
                userRepository.delete(existingRoot);
                log.info("Root user '{}' deleted", ROOT_USERNAME);
            }
        }
    }

    private void createRootUser() {
        log.info("Creating root user with username: {}", ROOT_USERNAME);
        MyUserEntity rootUser = new MyUserEntity();
        rootUser.addRole("root");
        rootUser.addRole("admin");
        rootUser.addRole("user");
        rootUser.setUsername(ROOT_USERNAME);
        rootUser.setPassword(passwordEncoder.encode(generateInitialPassword()));
        rootUser.setMandat("default");
        rootUser.setMustChangePassword(true);
        userRepository.save(rootUser);
        // SECURITY (forensics 23.08.2026): the initial password is NO LONGER LOGGED. Until now it stood in
        // clear text in the container log and thereby also in Graylog — a log reader was handed the
        // most powerful access of the application, and "appears only once" did not help,
        // because logs are retained. Instead the password is not made known at all
        // in the first place: it is a random throwaway value that nobody knows. Access is created
        // through the existing forgotten-password path (PasswordResetService, provided it is enabled
        // for the tenant) or by an already existing root user setting a password in the
        // user administration. mustChangePassword stays set, so that a password set that way
        // also has to be changed on the first login.
        log.warn("=== ROOT-USER '{}' angelegt === Es wurde ein zufaelliges Wegwerf-Passwort gesetzt, "
                + "das NIRGENDS ausgegeben wird. Zugang herstellen ueber 'Passwort vergessen' fuer "
                + "'{}' oder durch einen bestehenden root-Benutzer in der Benutzerverwaltung. "
                + "Ein Passwortwechsel wird beim ersten Login erzwungen.", ROOT_USERNAME, ROOT_USERNAME);
    }

    /**
     * Creates a random throwaway initial password (128 bits of entropy, 22 characters of Base64url) for
     * the root bootstrap user. Replaces the formerly static {@code "root"} (card 306). The
     * clear text leaves this method only as a bcrypt hash — it is deliberately not logged and
     * not returned (forensics 23.08.2026).
     */
    private static String generateInitialPassword() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

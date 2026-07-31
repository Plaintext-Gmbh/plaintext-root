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

    /** Quelle des Einmal-Initialpassworts fuer den Root-Bootstrap-User (Karte 306). */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final MyUserRepository userRepository;
    private final ISetupConfigService setupConfigService;
    /**
     * SECURITY (Karte 314, Punkt 7): zentrale {@link PasswordEncoder}-Bean statt eines lokalen
     * {@code new BCryptPasswordEncoder()}. Der lokale Aufruf haette den Spring-Default-Kostenfaktor
     * 10 behalten, waehrend die Bean in {@code PlaintextSecurityConfig} auf 12 steht — die
     * Kostenfaktoren waeren also je nach Codepfad auseinandergedriftet.
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
        String initialPassword = generateInitialPassword();
        MyUserEntity rootUser = new MyUserEntity();
        rootUser.addRole("root");
        rootUser.addRole("admin");
        rootUser.addRole("user");
        rootUser.setUsername(ROOT_USERNAME);
        rootUser.setPassword(passwordEncoder.encode(initialPassword));
        rootUser.setMandat("default");
        rootUser.setMustChangePassword(true);
        userRepository.save(rootUser);
        // SECURITY (Karte 306): KEIN statisches "root"-Passwort mehr. Das zufaellige Einmal-
        // Initialpasswort wird GENAU EINMAL hier ins Log geschrieben; der User wird beim ersten
        // Login zum Wechsel gezwungen (mustChangePassword). Danach ist es nirgends mehr abrufbar.
        log.warn("=== ROOT-USER '{}' angelegt — EINMAL-INITIALPASSWORT: {} === "
                + "Bitte SOFORT nach dem ersten Login aendern (Passwortwechsel wird erzwungen). "
                + "Dieses Passwort erscheint NUR EINMAL im Log.", ROOT_USERNAME, initialPassword);
    }

    /**
     * Erzeugt ein zufaelliges Initialpasswort (128 Bit Entropie, 22 Zeichen Base64url) fuer den
     * Root-Bootstrap-User. Ersetzt das frueher statische {@code "root"} (Karte 306).
     */
    private static String generateInitialPassword() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

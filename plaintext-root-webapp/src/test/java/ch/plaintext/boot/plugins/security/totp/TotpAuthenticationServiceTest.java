/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.totp;

import ch.plaintext.boot.plugins.security.PlaintextSecurityProperties;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.settings.ISetupConfigService;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit-Tests fuer {@link TotpAuthenticationService}: die sicherheitsrelevanten Entscheidungen
 * (Gate-Bedingung, TOTP-Verify, Recovery-Code one-time, Aktivieren/Deaktivieren) gegen eine
 * gemockte User-Persistenz.
 */
@ExtendWith(MockitoExtension.class)
class TotpAuthenticationServiceTest {

    @Mock
    private MyUserRepository userRepository;

    @Mock
    private ISetupConfigService setupConfigService;

    private TotpService totpService;
    private PlaintextSecurityProperties securityProperties;
    private TotpAuthenticationService service;

    private final DefaultCodeGenerator codeGenerator = new DefaultCodeGenerator();
    private final SystemTimeProvider timeProvider = new SystemTimeProvider();

    @BeforeEach
    void setUp() {
        securityProperties = new PlaintextSecurityProperties();
        securityProperties.getTotp().setEnabled(true);
        securityProperties.getTotp().setAllowedTimePeriodDiscrepancy(1);
        totpService = new TotpService(securityProperties);
        service = new TotpAuthenticationService(totpService, userRepository, securityProperties, setupConfigService);
    }

    private String validCode(String secret) throws Exception {
        long counter = timeProvider.getTime() / 30;
        return codeGenerator.generate(secret, counter);
    }

    private MyUserEntity userWithTotp(String secret, Set<String> hashedRecovery) {
        MyUserEntity u = new MyUserEntity();
        u.setUsername("alice");
        u.setPassword("$2a$hash");
        u.setTotpSecret(secret);
        u.setTotpEnabled(true);
        u.setRecoveryCodes(hashedRecovery != null ? hashedRecovery : new HashSet<>());
        return u;
    }

    // === isTotpRequired: die Bypass-relevante Gate-Bedingung ===

    @Test
    void isTotpRequired_falseWennFeatureGlobalAus() {
        securityProperties.getTotp().setEnabled(false);
        when(setupConfigService.isTotpEnabledAnywhere()).thenReturn(false);
        // Kein Repository-Zugriff noetig, wenn Feature (weder Property noch DB-Flag) aus ist.
        assertFalse(service.isTotpRequired("alice"));
        verifyNoInteractions(userRepository);
    }

    // === Feature-Gate: statische Property ODER DB-Flag (Root->Setup-UI) ===

    @Test
    void isFeatureEnabled_trueWennNurProperty() {
        securityProperties.getTotp().setEnabled(true);
        // OR kurzschliesst auf der Property -> DB-Flag wird nicht befragt.
        assertTrue(service.isFeatureEnabled());
        verifyNoInteractions(setupConfigService);
    }

    @Test
    void isFeatureEnabled_trueWennNurDbFlag() {
        securityProperties.getTotp().setEnabled(false);
        when(setupConfigService.isTotpEnabledAnywhere()).thenReturn(true);
        assertTrue(service.isFeatureEnabled(),
                "DB-Flag aus der Root->Setup-UI muss das Feature auch bei Property=false aktivieren");
    }

    @Test
    void isFeatureEnabled_falseWennBeidesAus() {
        securityProperties.getTotp().setEnabled(false);
        when(setupConfigService.isTotpEnabledAnywhere()).thenReturn(false);
        assertFalse(service.isFeatureEnabled(),
                "Ohne Property UND ohne DB-Flag bleibt das Feature aus (PROD-sicher, default-OFF)");
    }

    @Test
    void isTotpRequired_trueWennDbFlagAnUndUserAktiviert() {
        securityProperties.getTotp().setEnabled(false);
        when(setupConfigService.isTotpEnabledAnywhere()).thenReturn(true);
        when(userRepository.findByUsername("alice")).thenReturn(userWithTotp("SECRET234", null));
        assertTrue(service.isTotpRequired("alice"),
                "Bei aktivem DB-Flag muss ein User mit eingerichtetem TOTP den zweiten Faktor benoetigen");
    }

    @Test
    void isTotpRequired_trueWennFeatureAnUndUserAktiviert() {
        when(userRepository.findByUsername("alice")).thenReturn(userWithTotp("SECRET234", null));
        assertTrue(service.isTotpRequired("alice"));
    }

    @Test
    void isTotpRequired_falseWennUserTotpNichtAktiviert() {
        MyUserEntity u = new MyUserEntity();
        u.setUsername("bob");
        u.setTotpEnabled(false);
        when(userRepository.findByUsername("bob")).thenReturn(u);
        assertFalse(service.isTotpRequired("bob"));
    }

    @Test
    void isTotpRequired_falseWennKeinSecret() {
        MyUserEntity u = new MyUserEntity();
        u.setUsername("carol");
        u.setTotpEnabled(true);
        u.setTotpSecret(null);
        when(userRepository.findByUsername("carol")).thenReturn(u);
        assertFalse(service.isTotpRequired("carol"), "totpEnabled ohne Secret darf nicht als 'required' zaehlen");
    }

    @Test
    void isTotpRequired_falseWennUserUnbekannt() {
        when(userRepository.findByUsername("ghost")).thenReturn(null);
        assertFalse(service.isTotpRequired("ghost"));
    }

    // === verifySecondFactor: TOTP-Code ===

    @Test
    void verifySecondFactor_akzeptiertGueltigenTotpCode() throws Exception {
        String secret = totpService.generateSecret();
        when(userRepository.findByUsername("alice")).thenReturn(userWithTotp(secret, null));
        assertTrue(service.verifySecondFactor("alice", validCode(secret)));
    }

    @Test
    void verifySecondFactor_lehntFalschenCodeAb() {
        String secret = totpService.generateSecret();
        when(userRepository.findByUsername("alice")).thenReturn(userWithTotp(secret, null));
        assertFalse(service.verifySecondFactor("alice", "000000"));
    }

    // === verifySecondFactor: Recovery-Code one-time ===

    @Test
    void verifySecondFactor_recoveryCodeIstEinmalGueltig() {
        String secret = totpService.generateSecret();
        String plainRecovery = "ABCD-EFGH-JKLM";
        Set<String> hashed = new HashSet<>();
        hashed.add(totpService.hashRecoveryCode(plainRecovery));
        MyUserEntity user = userWithTotp(secret, hashed);
        when(userRepository.findByUsername("alice")).thenReturn(user);

        // Erste Nutzung: gueltig, Code wird verbraucht + gespeichert.
        assertTrue(service.verifySecondFactor("alice", plainRecovery));
        assertTrue(user.getRecoveryCodes().isEmpty(), "Recovery-Code muss nach Nutzung entfernt sein");
        verify(userRepository).save(user);

        // Zweite Nutzung desselben Codes: ungueltig (one-time).
        assertFalse(service.verifySecondFactor("alice", plainRecovery),
                "Bereits eingeloester Recovery-Code darf nicht erneut funktionieren");
    }

    @Test
    void verifySecondFactor_lehntUnbekanntenRecoveryCodeAb() {
        String secret = totpService.generateSecret();
        Set<String> hashed = new HashSet<>();
        hashed.add(totpService.hashRecoveryCode("ABCD-EFGH-JKLM"));
        when(userRepository.findByUsername("alice")).thenReturn(userWithTotp(secret, hashed));
        assertFalse(service.verifySecondFactor("alice", "ZZZZ-ZZZZ-ZZZZ"));
    }

    // === confirmAndEnable / disable ===

    @Test
    void confirmAndEnable_aktiviert2faUndGibtRecoveryCodesZurueck() throws Exception {
        String secret = totpService.generateSecret();
        MyUserEntity u = new MyUserEntity();
        u.setUsername("alice");
        u.setPassword("$2a$hash");
        when(userRepository.findByUsername("alice")).thenReturn(u);

        List<String> codes = service.confirmAndEnable("alice", secret, validCode(secret));
        assertNotNull(codes);
        assertFalse(codes.isEmpty());
        assertTrue(u.isTotpEnabled());
        assertEquals(secret, u.getTotpSecret());
        assertFalse(u.getRecoveryCodes().isEmpty(), "Gehashte Recovery-Codes muessen gespeichert sein");
        // Klartext-Codes duerfen NICHT im gespeicherten Set liegen.
        for (String plain : codes) {
            assertFalse(u.getRecoveryCodes().contains(plain));
        }
        verify(userRepository).save(u);
    }

    @Test
    void confirmAndEnable_lehntFalschenBestaetigungscodeAb() {
        String secret = totpService.generateSecret();
        MyUserEntity u = new MyUserEntity();
        u.setUsername("alice");
        when(userRepository.findByUsername("alice")).thenReturn(u);

        assertNull(service.confirmAndEnable("alice", secret, "000000"));
        assertFalse(u.isTotpEnabled());
        verify(userRepository, never()).save(any());
    }

    @Test
    void confirmAndEnable_lehntPasswordlessUserAb() throws Exception {
        String secret = totpService.generateSecret();
        MyUserEntity u = new MyUserEntity();
        u.setUsername("oidc");
        u.setPasswordless(true);
        when(userRepository.findByUsername("oidc")).thenReturn(u);

        assertNull(service.confirmAndEnable("oidc", secret, validCode(secret)),
                "OIDC-only-User duerfen kein TOTP einrichten");
        assertFalse(u.isTotpEnabled());
    }

    @Test
    void disable_loeschtSecretUndCodes() {
        Set<String> hashed = new HashSet<>();
        hashed.add(totpService.hashRecoveryCode("ABCD-EFGH-JKLM"));
        MyUserEntity u = userWithTotp("SECRET234", hashed);
        when(userRepository.findByUsername("alice")).thenReturn(u);

        service.disable("alice");
        assertFalse(u.isTotpEnabled());
        assertNull(u.getTotpSecret());
        assertTrue(u.getRecoveryCodes().isEmpty());
        verify(userRepository).save(u);
    }
}

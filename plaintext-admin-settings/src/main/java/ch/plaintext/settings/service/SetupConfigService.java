/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.service;

import ch.plaintext.settings.ISetupConfigService;
import ch.plaintext.settings.entity.SetupConfig;
import ch.plaintext.settings.repository.SetupConfigRepository;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Named("setupConfigService")
@Slf4j
@RequiredArgsConstructor
public class SetupConfigService implements ISetupConfigService {

    private final SetupConfigRepository repository;

    @Override
    public boolean isOidcAutoRedirectEnabled(String mandat) {
        return repository.findByMandat(mandat)
                .map(SetupConfig::isOidcAutoRedirectEnabled)
                .orElse(false);
    }

    @Override
    public Long getOidcAutoRedirectConfigId(String mandat) {
        return repository.findByMandat(mandat)
                .map(SetupConfig::getOidcAutoRedirectConfigId)
                .orElse(null);
    }

    @Override
    public boolean isPasswordManagementEnabled(String mandat) {
        return repository.findByMandat(mandat)
                .map(SetupConfig::isPasswordManagementEnabled)
                .orElse(true);
    }

    @Override
    public boolean isRootUserEnabled(String mandat) {
        // SECURITY (Karte 306): Fehlt eine Konfiguration (frische Installation), ist der
        // Root-Bootstrap-User AUS. Bestandsinstallationen haben eine persistierte Row und
        // liefern deren gespeicherten Wert -> ihr Zugang bleibt unangetastet.
        return repository.findByMandat(mandat)
                .map(SetupConfig::isRootUserEnabled)
                .orElse(false);
    }

    @Override
    public boolean isSelfRegistrationEnabled(String mandat) {
        return repository.findByMandat(mandat)
                .map(SetupConfig::isSelfRegistrationEnabled)
                .orElse(false);
    }

    @Override
    public boolean isPasswordResetLinkEnabled(String mandat) {
        return repository.findByMandat(mandat)
                .map(SetupConfig::isPasswordResetLinkEnabled)
                .orElse(false);
    }

    @Override
    public boolean isMagicLinkEnabled(String mandat) {
        return repository.findByMandat(mandat)
                .map(SetupConfig::isMagicLinkEnabled)
                .orElse(false);
    }

    @Override
    public Long getSystemMailAccountId() {
        // GLOBAL-Systemkonto ist system-weit (nur ROOT konfiguriert es) -> erste Konfiguration mit gesetztem Wert.
        return repository.findFirstBySystemMailAccountIdNotNull()
                .map(SetupConfig::getSystemMailAccountId)
                .orElse(null);
    }

    public Optional<SetupConfig> findByMandat(String mandat) {
        return repository.findByMandat(mandat);
    }

    public Optional<SetupConfig> findFirstWithOidcAutoRedirect() {
        return repository.findFirstByOidcAutoRedirectEnabledTrue();
    }

    public boolean isPasswordManagementDisabledAnywhere() {
        return repository.findFirstByPasswordManagementEnabledFalse().isPresent();
    }

    public boolean isSelfRegistrationEnabledAnywhere() {
        return repository.findFirstBySelfRegistrationEnabledTrue().isPresent();
    }

    public boolean isPasswordResetLinkEnabledAnywhere() {
        return repository.findFirstByPasswordResetLinkEnabledTrue().isPresent();
    }

    public boolean isMagicLinkEnabledAnywhere() {
        return repository.findFirstByMagicLinkEnabledTrue().isPresent();
    }

    @Override
    public boolean isTotpEnabledAnywhere() {
        return repository.findFirstByTotpEnabledTrue().isPresent();
    }

    @Transactional
    public SetupConfig save(SetupConfig config) {
        SetupConfig saved = repository.save(config);
        log.info("SetupConfig saved: id={}, mandat={}, oidcRedirect={}, rootUser={}, totp={}",
                saved.getId(), saved.getMandat(), saved.isOidcAutoRedirectEnabled(), saved.isRootUserEnabled(), saved.isTotpEnabled());
        return saved;
    }

    @Transactional
    public SetupConfig getOrCreate(String mandat) {
        return repository.findByMandat(mandat).orElseGet(() -> {
            SetupConfig config = new SetupConfig();
            config.setMandat(mandat);
            return repository.save(config);
        });
    }
}

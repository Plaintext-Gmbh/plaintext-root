/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.oidc.service;

import ch.plaintext.oidc.entity.OidcConfig;
import ch.plaintext.oidc.repository.OidcConfigRepository;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Service
@Named("oidcConfigService")
@Slf4j
@RequiredArgsConstructor
public class OidcConfigService {

    private final OidcConfigRepository repository;

    public List<OidcConfig> findAll() {
        return repository.findAll();
    }

    public Optional<OidcConfig> getActiveConfig() {
        return repository.findFirstByEnabledTrue();
    }

    public List<OidcConfig> getActiveConfigs() {
        return repository.findByEnabledTrue();
    }

    public boolean isOidcEnabled() {
        return !repository.findByEnabledTrue().isEmpty();
    }

    public String getActiveButtonLabel() {
        return getActiveConfig().map(OidcConfig::getButtonLabel).orElse("Login");
    }

    public String getActiveButtonIcon() {
        return getActiveConfig().map(OidcConfig::getButtonIcon).orElse("pi pi-sign-in");
    }

    @Transactional
    public OidcConfig save(OidcConfig config) {
        OidcConfig saved = repository.save(config);
        log.info("OIDC config saved: id={}, name={}, enabled={}", saved.getId(), saved.getName(), saved.isEnabled());
        return saved;
    }

    @Transactional
    public void delete(OidcConfig config) {
        if (config != null && config.getId() != null) {
            repository.delete(config);
            log.info("OIDC config deleted: id={}", config.getId());
        }
    }

    public String testConnection(OidcConfig config) {
        if (config == null || config.getIssuerUrl() == null || config.getIssuerUrl().isBlank()) {
            return "Issuer-URL ist leer";
        }

        String wellKnownUrl = config.getIssuerUrl().replaceAll("/+$", "") + "/.well-known/openid-configuration";

        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()) {

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(wellKnownUrl))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 && response.body().contains("issuer")) {
                log.info("OIDC connection test successful: {}", wellKnownUrl);
                return "OK";
            } else {
                log.warn("OIDC connection test failed: status={}", response.statusCode());
                return "HTTP " + response.statusCode() + " - Unerwartete Antwort";
            }
        } catch (Exception e) {
            log.error("OIDC connection test error: {}", e.getMessage());
            return "Fehler: " + e.getMessage();
        }
    }
}

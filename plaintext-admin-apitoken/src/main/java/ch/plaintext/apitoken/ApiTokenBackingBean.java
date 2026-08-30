/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.apitoken;

import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Backing Bean for API Token management UI.
 * Supports multiple named tokens per user.
 * <p>
 * Tokens are only shown once at creation time (like GitHub PATs).
 * After creation, only metadata (name, expiry, last used) is displayed.
 *
 * @author Plaintext GmbH
 * @since 2026
 */
@Component("apiTokenBean")
@Scope("session")
@Slf4j
public class ApiTokenBackingBean implements Serializable {

    @Autowired
    private transient ApiTokenService apiTokenService;

    @Getter
    private List<TokenDisplay> tokens = new ArrayList<>();

    @Getter
    @Setter
    private String newTokenName;

    @Getter
    @Setter
    private int newTokenValidityDays = JwtTokenService.DEFAULT_VALIDITY_DAYS;

    /**
     * Permission scope of the new token (card 312): {@code READ}, {@code WRITE} or
     * {@code ADMIN}. The default is deliberately {@code READ} — whoever needs more picks it
     * actively. Previously the issuing passed no scope at all, and the MCP filter read that as
     * ADMIN; that made every token created here a full-access token.
     */
    @Getter
    @Setter
    private String newTokenScope = "READ";

    /**
     * Selectable values for the scope, ascending by permission.
     *
     * <p><b>{@code WRITE} instead of {@code EINTRAGEN} (card 545, decision by Daniel 05.08.2026):</b>
     * the old name is still <em>accepted</em> by {@link McpBearerTokenFilter}, but no longer
     * <em>offered</em> here — existing tokens keep running unchanged, new legacy tokens are no
     * longer created. Both values grant the same authorities.</p>
     *
     * <p><b>{@code SESSION} has been dropped here (card 560, 05.08.2026).</b> The value was not an
     * MCP permission level, but the marker that a browser session may be built from the token via
     * {@code /token-login} — and that endpoint no longer exists. Continuing to offer the value
     * would have produced tokens that can no longer do anything: the
     * MCP bearer filter does not know {@code SESSION} and only grants {@code SCOPE_READ} for it.</p>
     *
     * <p>As with {@code EINTRAGEN}, the value is merely no longer <em>offered</em>. Existing
     * tokens with {@code scope=SESSION} remain valid and behave unchanged like
     * {@code READ} tokens; there is no migration and nobody is locked out.</p>
     */
    @Getter
    private final List<String> verfuegbareScopes = List.of("READ", "WRITE", "ADMIN");

    @Getter
    private int minValidityDays = JwtTokenService.MIN_VALIDITY_DAYS;

    @Getter
    private int maxValidityDays = JwtTokenService.MAX_VALIDITY_DAYS;

    /**
     * Transient: holds the newly created JWT token string.
     * Only available once after creation - cleared on next page load.
     */
    @Getter
    private String newlyCreatedToken;

    /**
     * Name of the newly created token (for display in the dialog).
     */
    @Getter
    private String newlyCreatedTokenName;

    @Value("${plaintext.baseurl:http://localhost:8080}")
    private String baseUrl;

    @Value("${plaintext.apitoken.endpoint:/api}")
    private String apiEndpointPath;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /**
     * preRenderView listener (session-scoped instead of @ViewScoped): loads the tokens FRESH on
     * every page call (GET). The isPostback guard prevents a reload on every Ajax postback.
     */
    public void onLoad() {
        FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null && ctx.isPostback()) {
            return;
        }
        loadTokens();
    }

    private void loadTokens() {
        Long userId = PlaintextSecurityHolder.getId();
        if (userId == null) {
            log.warn("No authenticated user found, cannot load API tokens");
            return;
        }
        String mandat = PlaintextSecurityHolder.getMandat();

        List<ApiToken> apiTokens = apiTokenService.getAllTokens(userId, mandat);
        this.tokens = new ArrayList<>();

        for (ApiToken t : apiTokens) {
            TokenDisplay display = new TokenDisplay();
            display.setId(t.getId());
            display.setTokenName(t.getTokenName() != null ? t.getTokenName() : "Unbenannt");
            display.setCreatedAt(formatDate(t.getCreatedAt()));
            display.setLastUsedAt(t.getLastUsedAt() != null ? formatDate(t.getLastUsedAt()) : "Noch nie");
            display.setExpiresAt(formatDate(t.getExpiresAt()));
            display.setExpiresSoon(apiTokenService.willExpireSoon(t, Duration.ofDays(7)));
            display.setExpired(t.isExpired());
            display.setUserEmail(t.getUserEmail());
            display.setUseCount(t.getUseCount());

            if (t.getExpiresAt() != null) {
                long daysUntilExpiry = Duration.between(LocalDateTime.now(), t.getExpiresAt()).toDays();
                display.setDaysUntilExpiry(daysUntilExpiry);
            }

            this.tokens.add(display);
        }
    }

    private String formatDate(LocalDateTime date) {
        return date != null ? date.format(DATE_FORMAT) : "-";
    }

    /**
     * Create a new token with the given name.
     * The JWT is stored in {@link #newlyCreatedToken} and shown once in a dialog.
     */
    public void createToken() {
        Long userId = PlaintextSecurityHolder.getId();
        if (userId == null) {
            addError("Nicht eingeloggt - bitte zuerst anmelden.");
            return;
        }
        String mandat = PlaintextSecurityHolder.getMandat();

        if (newTokenName == null || newTokenName.trim().isEmpty()) {
            addError("Bitte einen Namen für das Token eingeben.");
            return;
        }

        try {
            String jwt = apiTokenService.createToken(userId, mandat, newTokenName.trim(), PlaintextSecurityHolder.getUser(), newTokenValidityDays, newTokenScope);
            this.newlyCreatedToken = jwt;
            this.newlyCreatedTokenName = newTokenName.trim();
            addInfo("Token '" + newTokenName + "' wurde erstellt (" + newTokenValidityDays + " Tage gültig, Zugriff: " + newTokenScope + "). Bitte jetzt kopieren - es wird nicht erneut angezeigt!");
            log.info("API token '{}' created for user {} in mandat {}, validity {} days, scope {}", newTokenName, userId, mandat, newTokenValidityDays, newTokenScope);
            newTokenName = null;
            newTokenValidityDays = JwtTokenService.DEFAULT_VALIDITY_DAYS;
            loadTokens();
        } catch (IllegalStateException e) {
            addError(e.getMessage());
        }
    }

    /**
     * Clear the one-time token display (after user has copied it).
     */
    public void dismissNewToken() {
        this.newlyCreatedToken = null;
        this.newlyCreatedTokenName = null;
    }

    /**
     * Regenerate a specific token.
     * The new JWT is shown once in the dialog.
     */
    public void regenerateToken(Long tokenId) {
        Long userId = PlaintextSecurityHolder.getId();
        if (userId == null) {
            addError("Nicht eingeloggt - bitte zuerst anmelden.");
            return;
        }
        String mandat = PlaintextSecurityHolder.getMandat();

        try {
            String jwt = apiTokenService.regenerateToken(tokenId, userId, mandat, PlaintextSecurityHolder.getUser());
            this.newlyCreatedToken = jwt;
            this.newlyCreatedTokenName = "Erneuertes Token";
            addWarning("Token wurde erneuert. Bitte jetzt kopieren - es wird nicht erneut angezeigt! Der alte Token ist nicht mehr gültig.");
            loadTokens();
        } catch (Exception e) {
            addError("Fehler beim Erneuern: " + e.getMessage());
        }
    }

    /**
     * Invalidate a specific token (soft-delete).
     */
    public void invalidateToken(Long tokenId) {
        Long userId = PlaintextSecurityHolder.getId();
        if (userId == null) {
            addError("Nicht eingeloggt - bitte zuerst anmelden.");
            return;
        }
        String mandat = PlaintextSecurityHolder.getMandat();

        apiTokenService.invalidateToken(tokenId, userId, mandat);
        addInfo("Token wurde invalidiert.");
        loadTokens();
    }

    /**
     * Get the API endpoint URL.
     */
    public String getApiEndpoint() {
        return baseUrl + apiEndpointPath;
    }

    /**
     * Get example curl command (using placeholder since token is not stored).
     */
    public String getCurlExample() {
        return "curl -H \"Authorization: Bearer <IHR_TOKEN>\" " + getApiEndpoint();
    }

    /**
     * Get example curl command with the newly created token.
     */
    public String getNewTokenCurlExample() {
        if (newlyCreatedToken == null) return "";
        return "curl -H \"Authorization: Bearer " + newlyCreatedToken + "\" " + getApiEndpoint();
    }

    public boolean hasTokens() {
        return !tokens.isEmpty();
    }

    public boolean hasNewlyCreatedToken() {
        return newlyCreatedToken != null;
    }

    private void addInfo(String message) {
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Erfolg", message));
    }

    private void addWarning(String message) {
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_WARN, "Hinweis", message));
    }

    private void addError(String message) {
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", message));
    }

    /**
     * Display model for tokens in the UI.
     * Does NOT contain the token value (only metadata).
     */
    @Getter
    @Setter
    public static class TokenDisplay implements Serializable {
        private Long id;
        private String tokenName;
        private String createdAt;
        private String lastUsedAt;
        private String expiresAt;
        private String userEmail;
        private boolean expiresSoon;
        private boolean expired;
        private long daysUntilExpiry;
        private long useCount;
    }
}

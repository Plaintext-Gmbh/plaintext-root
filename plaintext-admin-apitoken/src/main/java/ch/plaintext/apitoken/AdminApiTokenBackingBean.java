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

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Admin Backing Bean for API Token management.
 * Shows all tokens in the current mandat. Allows invalidation but not deletion.
 *
 * @author Plaintext GmbH
 * @since 2026
 */
@Component("adminApiTokenBean")
@Scope("session")
@Slf4j
public class AdminApiTokenBackingBean implements Serializable {

    @Autowired
    private transient ApiTokenService apiTokenService;

    @Getter
    private List<AdminTokenDisplay> tokens = new ArrayList<>();

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /**
     * preRenderView-Listener (session-scoped statt @ViewScoped): lädt die Tokens FRISCH bei jedem
     * Seitenaufruf (GET). Der isPostback-Guard verhindert das Neuladen bei jedem Ajax-Postback.
     */
    public void onLoad() {
        FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null && ctx.isPostback()) {
            return;
        }
        loadTokens();
    }

    private void loadTokens() {
        String mandat = PlaintextSecurityHolder.getMandat();
        if (mandat == null) {
            log.warn("No mandat found, cannot load admin API tokens");
            return;
        }

        List<ApiToken> apiTokens = apiTokenService.getAllTokensByMandat(mandat);
        this.tokens = new ArrayList<>();

        for (ApiToken t : apiTokens) {
            AdminTokenDisplay display = new AdminTokenDisplay();
            display.setId(t.getId());
            display.setTokenName(t.getTokenName() != null ? t.getTokenName() : "Unbenannt");
            display.setUserId(t.getUserId());
            display.setUserEmail(t.getUserEmail() != null ? t.getUserEmail() : "-");
            display.setCreatedAt(formatDate(t.getCreatedAt()));
            display.setLastUsedAt(t.getLastUsedAt() != null ? formatDate(t.getLastUsedAt()) : "Noch nie");
            display.setExpiresAt(formatDate(t.getExpiresAt()));
            display.setExpiresSoon(apiTokenService.willExpireSoon(t, Duration.ofDays(7)));
            display.setExpired(t.isExpired());
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
     * Invalidate a token (admin action). Does not delete from DB.
     */
    public void invalidateToken(Long tokenId) {
        String mandat = PlaintextSecurityHolder.getMandat();
        if (mandat == null) {
            addError("Kein Mandat gefunden.");
            return;
        }

        apiTokenService.invalidateTokenByAdmin(tokenId, mandat);
        addInfo("Token wurde invalidiert.");
        loadTokens();
    }

    public boolean hasTokens() {
        return !tokens.isEmpty();
    }

    private void addInfo(String message) {
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Erfolg", message));
    }

    private void addError(String message) {
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", message));
    }

    /**
     * Display model for admin token view.
     */
    @Getter
    @Setter
    public static class AdminTokenDisplay implements Serializable {
        private Long id;
        private String tokenName;
        private Long userId;
        private String userEmail;
        private String createdAt;
        private String lastUsedAt;
        private String expiresAt;
        private boolean expiresSoon;
        private boolean expired;
        private long daysUntilExpiry;
        private long useCount;
    }
}

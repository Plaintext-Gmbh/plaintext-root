/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.apitoken;

import ch.plaintext.boot.plugins.jsf.FacesMessages;
import ch.plaintext.PlaintextSecurity;
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
 * Root Backing Bean for API Token management.
 * Shows all tokens across all mandats. Only accessible by ROOT users.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Component("rootApiTokenBean")
@Scope("session")
@Slf4j
public class RootApiTokenBackingBean implements Serializable {

    @Autowired
    private transient ApiTokenService apiTokenService;

    @Autowired
    private transient PlaintextSecurity security;

    @Getter
    private List<RootTokenDisplay> tokens = new ArrayList<>();

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /**
     * preRenderView listener (session-scoped instead of @ViewScoped): locks out non-ROOT users
     * (redirect) and loads the tokens FRESH on every page call (GET). The isPostback guard prevents
     * the reload on every Ajax postback. Replaces the former @PostConstruct init() + checkAccess().
     */
    public void onLoad() {
        if (!security.ifGranted("ROLE_ROOT")) {
            try {
                FacesContext.getCurrentInstance().getExternalContext().redirect("/index.xhtml");
            } catch (Exception e) {
                log.error("Redirect failed", e);
            }
            return;
        }
        FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null && ctx.isPostback()) {
            return;
        }
        loadTokens();
    }

    private void loadTokens() {
        List<ApiToken> apiTokens = apiTokenService.getAllTokensAllMandats();
        this.tokens = new ArrayList<>();

        for (ApiToken t : apiTokens) {
            RootTokenDisplay display = new RootTokenDisplay();
            display.setId(t.getId());
            display.setMandat(t.getMandat() != null ? t.getMandat() : "-");
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

    public void invalidateToken(Long tokenId) {
        if (!security.ifGranted("ROLE_ROOT")) {
            addError("Kein Root-Zugriff.");
            return;
        }

        apiTokenService.invalidateTokenByRoot(tokenId);
        addInfo("Token wurde invalidiert.");
        loadTokens();
    }

    public boolean hasTokens() {
        return !tokens.isEmpty();
    }

    private void addInfo(String message) {
        FacesMessages.info("Erfolg", message);
    }

    private void addError(String message) {
        FacesMessages.error("Fehler", message);
    }

    @Getter
    @Setter
    public static class RootTokenDisplay implements Serializable {
        private Long id;
        private String mandat;
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

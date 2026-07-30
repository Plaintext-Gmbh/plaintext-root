/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.web;

import ch.plaintext.boot.menu.MenuAnnotation;
import ch.plaintext.sessions.model.SessionAttribute;
import ch.plaintext.sessions.entity.UserSession;
import ch.plaintext.sessions.repository.UserSessionRepository;
import ch.plaintext.sessions.service.HttpSessionRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpSession;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Session Inspector Backing Bean for Root users
 * Displays all session attributes with their names and sizes
 *
 * @author info@plaintext.ch
 * @since 2024
 */
@Component
@Named("sessionInspectorBackingBean")
@Data
@Slf4j
@Scope(scopeName = "request")
@MenuAnnotation(
    title = "Session Insights",
    link = "sessioninsights.html",
    parent = "Root",
    order = 110,
    icon = "pi pi-chart-bar",
    roles = {"ROOT"}
)
public class SessionInspectorBackingBean implements Serializable {
    private static final long serialVersionUID = 1L;

    private final UserSessionRepository userSessionRepository;
    private final HttpSessionRegistry sessionRegistry;

    private List<SessionAttribute> sessionAttributes = new ArrayList<>();
    private long totalSize = 0;
    private String formattedTotalSize;
    private String selectedSessionId;
    private List<UserSessionInfo> activeSessions = new ArrayList<>();

    public SessionInspectorBackingBean(UserSessionRepository userSessionRepository,
                                      HttpSessionRegistry sessionRegistry) {
        this.userSessionRepository = userSessionRepository;
        this.sessionRegistry = sessionRegistry;
    }

    // Inner class to represent active user sessions
    @Data
    public static class UserSessionInfo {
        private String sessionId;
        private String username;
        private String displayName;

        public UserSessionInfo(String sessionId, String username) {
            this.sessionId = sessionId;
            this.username = username;
            this.displayName = username + " (Session: " + sessionId.substring(0, Math.min(8, sessionId.length())) + "...)";
        }
    }

    @PostConstruct
    public void init() {
        loadActiveSessions();
        loadSessionAttributes();
    }

    private void loadActiveSessions() {
        try {
            List<UserSession> activeSessions = userSessionRepository.findAllByActive(true);
            this.activeSessions = activeSessions.stream()
                    .map(us -> new UserSessionInfo(us.getSessionId(), us.getUsername()))
                    .collect(Collectors.toList());

            log.info("Loaded {} active sessions", this.activeSessions.size());

            // Set the current session as selected by default
            FacesContext facesContext = FacesContext.getCurrentInstance();
            if (facesContext != null) {
                ExternalContext externalContext = facesContext.getExternalContext();
                HttpSession session = (HttpSession) externalContext.getSession(false);
                if (session != null) {
                    selectedSessionId = session.getId();
                }
            }
        } catch (Exception e) {
            log.error("Error loading active sessions", e);
        }
    }

    public void onUserChange() {
        log.info("User changed to session: {}", selectedSessionId);
        loadSessionAttributes();
    }

    public void loadSessionAttributes() {
        sessionAttributes.clear();
        totalSize = 0;

        try {
            HttpSession targetSession = null;

            // If no session is selected or session not found in registry, use current session
            if (selectedSessionId == null || selectedSessionId.isEmpty()) {
                // Use current user's session as default
                FacesContext facesContext = FacesContext.getCurrentInstance();
                if (facesContext != null) {
                    ExternalContext externalContext = facesContext.getExternalContext();
                    targetSession = (HttpSession) externalContext.getSession(false);
                }
            } else {
                // Try to get the selected session from registry
                Optional<HttpSession> optSession = sessionRegistry.getSession(selectedSessionId);
                if (optSession.isPresent()) {
                    targetSession = optSession.get();
                    log.info("Viewing session {} from registry", selectedSessionId);
                } else {
                    log.warn("Session {} not found in registry", selectedSessionId);
                    sessionAttributes.add(new SessionAttribute(
                            "WARNUNG",
                            "Die ausgewählte Session wurde nicht im Registry gefunden. " +
                            "Möglicherweise ist der Benutzer nicht mehr angemeldet.",
                            0
                    ));
                    formattedTotalSize = "0 B";
                    return;
                }
            }

            if (targetSession == null) {
                log.warn("No session available");
                sessionAttributes.add(new SessionAttribute(
                        "FEHLER",
                        "Keine Session verfügbar.",
                        0
                ));
                formattedTotalSize = "0 B";
                return;
            }

            Enumeration<String> attributeNames = targetSession.getAttributeNames();
            while (attributeNames.hasMoreElements()) {
                String name = attributeNames.nextElement();
                Object value = targetSession.getAttribute(name);

                long size = calculateSize(value);
                totalSize += size;

                SessionAttribute attr = new SessionAttribute(name, value, size);
                sessionAttributes.add(attr);
            }

            // Sort by size (largest first)
            Collections.sort(sessionAttributes, (a, b) -> Long.compare(b.getSizeInBytes(), a.getSizeInBytes()));

            formattedTotalSize = formatSize(totalSize);

            log.info("Loaded {} session attributes with total size: {}", sessionAttributes.size(), formattedTotalSize);

        } catch (Exception e) {
            log.error("Error loading session attributes", e);
        }
    }

    private long calculateSize(Object obj) {
        if (obj == null) {
            return 0;
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(obj);
            oos.flush();
            return baos.size();
        } catch (Exception e) {
            log.warn("Could not serialize object of type {}: {}", obj.getClass().getName(), e.getMessage());
            // Return estimated size based on class name
            return obj.getClass().getName().length() + 100;
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        }
    }

    public void refresh() {
        loadSessionAttributes();
        log.debug("Session attributes refreshed");
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.plugins.log.Log;
import ch.plaintext.boot.plugins.security.impersonation.ImpersonationAudit;
import ch.plaintext.boot.plugins.security.impersonation.ImpersonationAuditRepository;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.model.UserMandate;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.boot.plugins.security.persistence.UserMandateRepository;
import ch.plaintext.menuesteuerung.model.MandateMenuConfig;
import ch.plaintext.menuesteuerung.persistence.MandateMenuConfigRepository;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Provides static access to security-related functionality.
 * This class allows static method calls to retrieve current user information
 * from the Spring Security context.
 */
@Component
@Named("plaintextSecurity")
@Slf4j
public class PlaintextSecurityImpl implements PlaintextSecurity {

    private static final String SESSION_ORIGINAL_USER_ID = "impersonation.originalUserId";
    private static final String SESSION_ORIGINAL_AUTH = "impersonation.originalAuth";
    private static final String SYSTEM_USER = "SYSTEM";

    private static PlaintextSecurityImpl instance;

    private final MyUserRepository userRepository;
    private final MandateMenuConfigRepository mandateMenuConfigRepository;
    private final UserMandateRepository userMandateRepository;
    private final ImpersonationAuditRepository impersonationAuditRepository;

    /**
     * Only needed to name the session-scoped beans that have to go when the role changes.
     * Wired optionally, so that lean contexts (tests without a full
     * application context) can still build the bean.
     */
    private final ObjectProvider<ConfigurableListableBeanFactory> beanFactory;

    public PlaintextSecurityImpl(MyUserRepository userRepository,
                                  MandateMenuConfigRepository mandateMenuConfigRepository,
                                  UserMandateRepository userMandateRepository,
                                  ImpersonationAuditRepository impersonationAuditRepository,
                                  ObjectProvider<ConfigurableListableBeanFactory> beanFactory) {
        this.userRepository = userRepository;
        this.mandateMenuConfigRepository = mandateMenuConfigRepository;
        this.userMandateRepository = userMandateRepository;
        this.impersonationAuditRepository = impersonationAuditRepository;
        this.beanFactory = beanFactory;
    }

    /**
     * Throws all session-scoped beans out of the session, so that they rebuild themselves on the
     * next access.
     *
     * <p><b>Why this is needed (report by Daniel, 25.08.2026).</b> "My account" kept showing the
     * previous user after impersonating. The reason lies here: neither
     * {@link #startImpersonation(Long)} nor {@link #switchActiveMandat(String)} ever removed anything
     * from the session — only the {@code Authentication} in the
     * {@code SecurityContextHolder} was swapped. The comment in startImpersonation had always read
     * "clear all attributes except security-related ones"; the code contained not a single
     * {@code removeAttribute}. Every session-scoped bean that builds its state once in
     * {@code @PostConstruct} therefore stayed with the old user.
     *
     * <p><b>Why not simply delete everything.</b> The same session holds the
     * Spring Security context, the impersonation markers and the JSF view state. Blanket
     * clearing would log the user out or destroy the running view.
     * That is why exclusively those attributes are removed that are demonstrably <b>Spring beans with
     * scope "session"</b> — the list comes from the bean definitions, not from a
     * naming rule. Both storage forms are thereby covered: the plain bean name and the
     * {@code scopedTarget.} name of a bean with a scope proxy.
     *
     * <p>Removal goes through {@code RequestAttributes}, not through {@code HttpSession}, so that the
     * cleanup callbacks registered with Spring ({@code @PreDestroy}) run as usual.
     */
    // Package-visible instead of private, so that the test can measure exactly this step.
    void verwerfeSessionBeans() {
        ConfigurableListableBeanFactory factory = beanFactory == null ? null : beanFactory.getIfAvailable();
        if (factory == null) {
            log.debug("Session-Beans nicht verworfen - keine BeanFactory verfuegbar");
            return;
        }
        RequestAttributes attrs;
        try {
            attrs = RequestContextHolder.currentRequestAttributes();
        } catch (IllegalStateException e) {
            log.debug("Session-Beans nicht verworfen - kein Request-Kontext");
            return;
        }
        int verworfen = 0;
        for (String name : factory.getBeanDefinitionNames()) {
            try {
                if (!WebApplicationContext.SCOPE_SESSION.equals(factory.getBeanDefinition(name).getScope())) {
                    continue;
                }
                if (attrs.getAttribute(name, RequestAttributes.SCOPE_SESSION) != null) {
                    attrs.removeAttribute(name, RequestAttributes.SCOPE_SESSION);
                    verworfen++;
                }
            } catch (Exception e) {
                log.debug("Session-Bean '{}' konnte nicht verworfen werden: {}", name, e.getMessage());
            }
        }
        log.info("Rollenwechsel: {} session-scoped Bean(s) verworfen", verworfen);
    }

    @PostConstruct
    private void init() {
        PlaintextSecurityImpl.instance = this;
    }

    /**
     * Gets the mandat for the currently authenticated user.
     *
     * @return The mandat string from the user's roles, or "default" if no mandat role is found
     * Returns "NO_AUTH" if no authentication is present
     * Returns "NO_USER" if the user cannot be found in the database
     */
    @Override
    public String getMandat() {
        if (instance == null) {
            log.warn("PlaintextSecurityImpl instance not initialized");
            return "NO_INSTANCE";
        }
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null) {
                return "NO_AUTH";
            }
            for(GrantedAuthority role: auth.getAuthorities()){
                if(role.toString().toLowerCase().contains("mandat")){
                    String result = role.toString().toLowerCase().split("_")[role.toString().split("_").length - 1];
                    return result;
                }
            }
            return "default";
        } catch (Exception e) {
            log.error("Error getting mandat", e);
            return "ERROR";
        }
    }

    @Override
    public Set<String> getAllMandate() {
            Set<String> mandanten = new HashSet<>();

            try {
                // 1. load tenants from users
                List<MyUserEntity> allUsers = userRepository.findAll();
                for (MyUserEntity user : allUsers) {
                    String mandat = user.getMandat();
                    if (mandat != null && !mandat.trim().isEmpty()) {
                        mandanten.add(mandat.toLowerCase());
                    }
                }
                log.debug("Found {} unique mandanten from users: {}", mandanten.size(), mandanten);

                // 2. load tenants from MandateMenuConfig
                List<MandateMenuConfig> menuConfigs = mandateMenuConfigRepository.findAll();
                for (MandateMenuConfig config : menuConfigs) {
                    String mandat = config.getMandateName();
                    if (mandat != null && !mandat.trim().isEmpty()) {
                        mandanten.add(mandat.toLowerCase());
                    }
                }
                log.debug("Found {} total unique mandanten after adding menu configs: {}", mandanten.size(), mandanten);

            } catch (Exception e) {
                log.error("Error loading mandanten from database", e);
                // fall back to default
                mandanten.add("default");
            }

            // If no tenants were found, add default
            if (mandanten.isEmpty()) {
                log.warn("No mandanten found in database, using 'default'");
                mandanten.add("default");
            }

            return mandanten;
    }

    /**
     * Sets the mandat for the currently authenticated user by injecting
     * a role of the form "PROPERTY_MANDAT_<value>" into the SecurityContext
     * and persisting the mandat in the database for the current user.
     *
     * @param mandat The mandat to set (e.g. "dev", "admin", "test")
     */
    public void setMandat(String mandat) {
        try {
            // 1) set the tenant role in the SecurityContext
            if (!applyMandatAuthority(mandat)) {
                log.warn("No authentication present – cannot set mandat");
                return;
            }

            // 2) store the tenant as the home tenant in the database
            Long userId = getId(); // already uses the roles to pull out myuserid
            if (userId == null || userId <= 0) {
                log.warn("Cannot persist mandat – invalid userId: {}", userId);
                return;
            }

            userRepository.findById(userId).ifPresentOrElse(user -> {
                user.setMandat(mandat);
                userRepository.save(user);
                log.info("Mandat (DB) for user {} updated to {}", userId, mandat);
            }, () -> {
                log.warn("User with ID {} not found – cannot persist mandat", userId);
            });

        } catch (Exception e) {
            log.error("Error while setting mandat", e);
        }
    }

    /**
     * Swaps the {@code PROPERTY_MANDAT_} role in the SecurityContext (same principal,
     * same credentials). Does NOT persist to the database.
     *
     * @param mandat target tenant
     * @return true if a logged-in user was present and the role was set
     */
    private boolean applyMandatAuthority(String mandat) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        List<GrantedAuthority> newAuthorities = new ArrayList<>(auth.getAuthorities());
        newAuthorities.removeIf(a -> a.getAuthority().toLowerCase().contains("mandat"));
        String rolle = "PROPERTY_MANDAT_" + mandat.toLowerCase();
        newAuthorities.add(new SimpleGrantedAuthority(rolle));
        Authentication newAuth = new UsernamePasswordAuthenticationToken(
                auth.getPrincipal(), auth.getCredentials(), newAuthorities);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(newAuth);
        SecurityContextHolder.setContext(context);
        // Spring Security 6 no longer stores a context that is only set in the SecurityContextHolder
        // into the HTTP session automatically (SecurityContextHolderFilter instead of the old
        // SecurityContextPersistenceFilter). Without saving explicitly, the tenant switch lives
        // only in the current Ajax request; after the window.location.reload() of the dropdown
        // the new request loads the old context from the session -> the switch would be gone again.
        persistSecurityContextToSession(context);
        log.info("Mandat (SecurityContext) auf {} gesetzt", rolle);
        return true;
    }

    /**
     * Writes the (switched) SecurityContext explicitly into the HTTP session, so that the
     * tenant switch survives the following request (reload). Outside a web request
     * (e.g. tests/cron) nothing happens beyond the holder that has already been set.
     */
    private void persistSecurityContextToSession(SecurityContext context) {
        try {
            if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
                return;
            }
            HttpSession session = attrs.getRequest().getSession(true);
            session.setAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        } catch (Exception e) {
            log.warn("Aktiver Mandant konnte nicht in der Session persistiert werden", e);
        }
    }

    @Override
    public Set<String> getAllowedMandate() {
        // ROOT may switch between all tenants (as before).
        if (ifGranted("ROOT")) {
            return getAllMandate();
        }
        Set<String> allowed = new HashSet<>();
        String home = getMandat();
        if (home != null && !home.trim().isEmpty()) {
            allowed.add(home.toLowerCase());
        }
        try {
            String username = getUser();
            if (username != null && !SYSTEM_USER.equals(username)) {
                for (UserMandate um : userMandateRepository.findByUsernameAndActiveTrue(username)) {
                    if (um.getMandat() != null && !um.getMandat().trim().isEmpty()) {
                        allowed.add(um.getMandat().toLowerCase());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error loading zusätzliche Mandate für den Benutzer", e);
        }
        return allowed;
    }

    @Override
    public boolean isCanSwitchMandat() {
        // getAllowedMandate() already returns ALL tenants of the instance for ROOT -> no separate
        // ifGranted("ROOT") or-clause needed. Previously EVERY ROOT user saw the switcher, even when there
        // was only a single tenant instance-wide (pure confusion, nothing to select).
        return getAllowedMandate().size() > 1;
    }

    @Override
    public void switchActiveMandat(String mandat) {
        if (mandat == null || mandat.trim().isEmpty()) {
            return;
        }
        String target = mandat.toLowerCase();
        if (!getAllowedMandate().contains(target)) {
            log.warn("Mandat-Wechsel auf nicht erlaubten Mandanten '{}' abgelehnt (Benutzer {})",
                    target, getUser());
            return;
        }
        String previous = getMandat();
        // Remember permanently: persist the active tenant role in the DB (robust against remember-me
        // and new sessions) AND set it in the SecurityContext + the HTTP session. setMandat() does
        // both (authority swap + user.setMandat + save). Persisting the SecurityContext alone was not
        // enough: a new request/remember-me loaded the DB home tenant again.
        setMandat(target);
        // So that the user does NOT lose access to the previous tenant after the switch
        // (the former home tenant sat in the now overwritten PROPERTY_MANDAT_ role and
        // would otherwise drop out of getAllowedMandate), it is saved as an additional, switchable UserMandate.
        if (previous != null && !previous.equalsIgnoreCase(target)) {
            ensureSwitchableMandate(getUser(), previous);
        }
        verwerfeSessionBeans();
        log.info("Aktiver Mandant dauerhaft gewechselt zu {} (Benutzer {})", target, getUser());
    }

    /**
     * Makes sure that {@code mandat} is present as an active, switchable {@link UserMandate} of the user
     * (creates it if needed). This way a home tenant left behind by a switch stays
     * in {@link #getAllowedMandate()} and therefore selectable.
     */
    private void ensureSwitchableMandate(String username, String mandat) {
        if (username == null || SYSTEM_USER.equals(username) || mandat == null || mandat.isBlank()) {
            return;
        }
        String m = mandat.toLowerCase();
        boolean exists = userMandateRepository.findByUsername(username).stream()
                .anyMatch(um -> m.equalsIgnoreCase(um.getMandat()) && um.isActive());
        if (exists) {
            return;
        }
        UserMandate um = new UserMandate();
        um.setUsername(username);
        um.setMandat(m);
        um.setActive(true);
        userMandateRepository.save(um);
        log.info("Voriges Mandant '{}' als wechselbares UserMandate fuer '{}' gesichert", m, Log.mail(username));
    }

    /** EL getter for the tenant selection (current tenant). */
    public String getActiveMandat() {
        return getMandat();
    }

    /** EL setter: triggers the (validated, session-only) switch when the selection changes. */
    public void setActiveMandat(String mandat) {
        switchActiveMandat(mandat);
    }

    @Override
    public List<String> getUsernamesWithMandatAccess(String mandat) {
        Set<String> users = new HashSet<>(getUsersForMandat(mandat));
        if (mandat != null && !mandat.trim().isEmpty()) {
            try {
                for (UserMandate um : userMandateRepository.findByMandatAndActiveTrue(mandat.toLowerCase())) {
                    if (um.getUsername() != null && !um.getUsername().isBlank()) {
                        users.add(um.getUsername());
                    }
                }
            } catch (Exception e) {
                log.error("Error loading users with additional mandate access for {}", mandat, e);
            }
        }
        return new ArrayList<>(users);
    }


    @Override
    public Long getId() {
        if (instance == null) {
            log.warn("PlaintextSecurityImpl instance not initialized");
            return -1L;
        }
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            for(GrantedAuthority role: auth.getAuthorities()){
                if(role.toString().toLowerCase().contains("myuserid")){
                    String digits = role.toString().toLowerCase().replaceAll("[^0-9]", "");
                    return digits.isEmpty() ? -1L : Long.parseLong(digits);
                }
            }
            return -1L;
        } catch (Exception e) {
            log.error("Error getting mandat", e);
            return -1L;
        }
    }

    @Override
    public String getUser() {
        if (instance == null) {
            log.warn("PlaintextSecurityImpl instance not initialized");
            return SYSTEM_USER;
        }
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                log.debug("No authenticated user found, using SYSTEM");
                return SYSTEM_USER;
            }
            return auth.getName();
        } catch (Exception e) {
            log.error("Error getting user", e);
            return SYSTEM_USER;
        }
    }

    public Authentication getAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Override
    public String getMandatForUser(long userId) {
        if (instance == null) {
            log.warn("PlaintextSecurityImpl instance not initialized");
            return null;
        }
        try {
            MyUserEntity user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                log.warn("User with ID {} not found", userId);
                return null;
            }
            return user.getMandat();
        } catch (Exception e) {
            log.error("Error getting mandat for user {}", userId, e);
            return null;
        }
    }

    /**
     * Card 596: resolution of user id -&gt; user name, so that background runs can take the recipient
     * from the record. In a cron context {@link #getId()} returns {@code -1} (card 588),
     * so the security context is no usable source there.
     *
     * <p>Deliberately built identically to {@link #getMandatForUser(long)} — same safeguards,
     * same null semantics, so that the two behave the same.
     */
    @Override
    public String getUsernameForUser(long userId) {
        if (instance == null) {
            log.warn("PlaintextSecurityImpl instance not initialized");
            return null;
        }
        try {
            MyUserEntity user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                log.warn("User with ID {} not found", userId);
                return null;
            }
            return user.getUsername();
        } catch (Exception e) {
            log.error("Error getting username for user {}", userId, e);
            return null;
        }
    }

    @Override
    public boolean ifGranted(String role) {
        if (role == null) return false;
        String normalized = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return getAuthentication().getAuthorities().stream()
                .anyMatch(a -> normalized.equalsIgnoreCase(a.getAuthority()));
    }

    public List<String> getUsersForMandat(String mandat) {
        List<String> users = new ArrayList<>();

        if (mandat == null || mandat.trim().isEmpty()) {
            log.warn("Cannot get users for null or empty mandat");
            return users;
        }

        try {
            List<MyUserEntity> allUsers = userRepository.findAll();
            for (MyUserEntity user : allUsers) {
                String userMandat = user.getMandat();
                if (userMandat != null && userMandat.equalsIgnoreCase(mandat)) {
                    users.add(user.getUsername());
                    log.debug("Found user {} for mandat {}", Log.mail(user.getUsername()), mandat);
                }
            }
            log.info("Found {} users for mandat {}", users.size(), mandat);
        } catch (Exception e) {
            log.error("Error getting users for mandat {}", mandat, e);
        }

        return users;
    }

    @Override
    public String getStartpageOrDefault() {
        if (instance == null) {
            log.warn("PlaintextSecurityImpl instance not initialized");
            return "/index.html?faces-redirect=true";
        }

        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || auth.getAuthorities() == null) {
                return "/index.html?faces-redirect=true";
            }

            // Get startpage from properties
            String startpage = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(a -> a.startsWith("PROPERTY_STARTPAGE_"))
                    .map(a -> a.substring("PROPERTY_STARTPAGE_".length()))
                    .findFirst()
                    .orElse("N/A");

            // Check if startpage is null, empty, or "N/A"
            if (startpage == null || startpage.trim().isEmpty() || "N/A".equalsIgnoreCase(startpage)) {
                return "/index.html?faces-redirect=true";
            }

            // Ensure .xhtml or .html extension
            if (!startpage.endsWith(".xhtml") && !startpage.endsWith(".html")) {
                startpage = startpage + ".xhtml";
            }

            // Add leading slash if not present
            if (!startpage.startsWith("/")) {
                startpage = "/" + startpage;
            }

            return startpage + "?faces-redirect=true";
        } catch (Exception e) {
            log.error("Error getting startpage, returning default", e);
            return "/index.html?faces-redirect=true";
        }
    }

    @Override
    public boolean isImpersonating() {
        try {
            HttpSession session = getCurrentSession();
            if (session == null) {
                return false;
            }
            return session.getAttribute(SESSION_ORIGINAL_USER_ID) != null;
        } catch (Exception e) {
            log.error("Error checking impersonation status", e);
            return false;
        }
    }

    @Override
    public void startImpersonation(Long userId) {
        if (userId == null) {
            log.warn("Cannot start impersonation with null userId");
            return;
        }
        // Second line of defence: MyUserBackingBean.impersonateUser() checks isRoot() before
        // this call already, but that is only the so far ONLY caller -- no @PreAuthorize possible
        // (no @EnableMethodSecurity in the framework, so it would be annotated silently without effect), hence
        // an explicit check here directly in the security-critical method itself.
        if (!ifGranted("ROOT")) {
            log.warn("SECURITY: startImpersonation abgelehnt - Aufrufer ist nicht ROOT (Ziel-User {})", userId);
            return;
        }

        try {
            // Get current authentication and user ID
            Authentication currentAuth = SecurityContextHolder.getContext().getAuthentication();
            Long currentUserId = getId();

            if (currentUserId == null || currentUserId <= 0) {
                log.warn("Cannot start impersonation - invalid current user ID");
                return;
            }

            // Get the user to impersonate
            MyUserEntity targetUser = userRepository.findById(userId).orElse(null);
            if (targetUser == null) {
                log.warn("Cannot start impersonation - user {} not found", userId);
                return;
            }

            // Get session and clear all attributes except security-related ones
            HttpSession session = getCurrentSession();
            if (session == null) {
                log.warn("Cannot start impersonation - no session available");
                return;
            }

            // Store original authentication in session
            session.setAttribute(SESSION_ORIGINAL_USER_ID, currentUserId);
            session.setAttribute(SESSION_ORIGINAL_AUTH, currentAuth);

            // Build new authorities for target user
            List<GrantedAuthority> newAuthorities = new ArrayList<>();

            // Add user ID as authority
            newAuthorities.add(new SimpleGrantedAuthority("PROPERTY_MYUSERID_" + targetUser.getId()));

            // Add roles (with same logic as MyUserDetailsService)
            if (targetUser.getRoles() != null) {
                for (String role : targetUser.getRoles()) {
                    if (role.toLowerCase().contains("mandat")) {
                        newAuthorities.add(new SimpleGrantedAuthority(role.toUpperCase()));
                    } else {
                        newAuthorities.add(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
                    }
                }
            }

            // Add mandat
            if (targetUser.getMandat() != null && !targetUser.getMandat().isEmpty()) {
                newAuthorities.add(new SimpleGrantedAuthority("PROPERTY_MANDAT_" + targetUser.getMandat().toLowerCase()));
            }

            // Add startpage if available
            if (targetUser.getStartpage() != null && !targetUser.getStartpage().isEmpty()) {
                newAuthorities.add(new SimpleGrantedAuthority("PROPERTY_STARTPAGE_" + targetUser.getStartpage()));
            }

            // Create new authentication for target user
            Authentication newAuth = new UsernamePasswordAuthenticationToken(
                    targetUser.getUsername(),
                    currentAuth.getCredentials(),
                    newAuthorities
            );

            // Update security context
            SecurityContextHolder.getContext().setAuthentication(newAuth);

            // Only after the switch: a bean that rebuilds itself in between would otherwise
            // see the old identity again.
            verwerfeSessionBeans();

            recordImpersonationStart(currentUserId, currentAuth.getName(), userId, targetUser.getUsername(),
                    session.getId());

            log.info("Started impersonation: original user {} is now impersonating user {} ({})",
                    currentUserId, userId, Log.mail(targetUser.getUsername()));

        } catch (Exception e) {
            log.error("Error starting impersonation for user {}", userId, e);
        }
    }

    /** Audits the start of an impersonation (a queryable history instead of only a log). Best effort. */
    private void recordImpersonationStart(Long adminUserId, String adminUsername, Long targetUserId,
                                          String targetUsername, String sessionId) {
        try {
            ImpersonationAudit audit = new ImpersonationAudit();
            audit.setAdminUserId(adminUserId);
            audit.setAdminUsername(adminUsername);
            audit.setTargetUserId(targetUserId);
            audit.setTargetUsername(targetUsername);
            audit.setStartedAt(LocalDateTime.now());
            audit.setSessionId(sessionId);
            impersonationAuditRepository.save(audit);
        } catch (Exception e) {
            log.error("Konnte Impersonation-Audit-Eintrag (Start) nicht speichern", e);
        }
    }

    @Override
    public void stopImpersonation() {
        try {
            HttpSession session = getCurrentSession();
            if (session == null) {
                log.warn("Cannot stop impersonation - no session available");
                return;
            }

            Authentication originalAuth = (Authentication) session.getAttribute(SESSION_ORIGINAL_AUTH);
            Long originalUserId = (Long) session.getAttribute(SESSION_ORIGINAL_USER_ID);

            if (originalAuth == null) {
                log.warn("Cannot stop impersonation - no original authentication stored");
                return;
            }

            // Restore original authentication
            SecurityContextHolder.getContext().setAuthentication(originalAuth);

            // The way back needs it too: otherwise the beans keep the data of the
            // impersonated user.
            verwerfeSessionBeans();

            // Clear impersonation session attributes
            session.removeAttribute(SESSION_ORIGINAL_USER_ID);
            session.removeAttribute(SESSION_ORIGINAL_AUTH);

            recordImpersonationEnd(originalUserId);

            log.info("Stopped impersonation - restored original user {}", originalUserId);

        } catch (Exception e) {
            log.error("Error stopping impersonation", e);
        }
    }

    /** Audits the end of an impersonation (closes the open audit entry). Best effort. */
    private void recordImpersonationEnd(Long adminUserId) {
        try {
            impersonationAuditRepository
                    .findFirstByAdminUserIdAndEndedAtIsNullOrderByStartedAtDesc(adminUserId)
                    .ifPresent(audit -> {
                        audit.setEndedAt(LocalDateTime.now());
                        impersonationAuditRepository.save(audit);
                    });
        } catch (Exception e) {
            log.error("Konnte Impersonation-Audit-Eintrag (Ende) nicht schliessen", e);
        }
    }

    @Override
    public Long getOriginalUserId() {
        try {
            HttpSession session = getCurrentSession();
            if (session == null) {
                return null;
            }
            return (Long) session.getAttribute(SESSION_ORIGINAL_USER_ID);
        } catch (Exception e) {
            log.error("Error getting original user ID", e);
            return null;
        }
    }

    /**
     * Logout the current user and redirect to login page
     */
    public String logout() {
        try {
            log.info("Logging out user: {}", getUser());

            // Invalidate session
            HttpSession session = getCurrentSession();
            if (session != null) {
                session.invalidate();
            }

            // Clear security context
            SecurityContextHolder.clearContext();

            return "/login.html?faces-redirect=true";
        } catch (Exception e) {
            log.error("Error during logout", e);
            return "/login.html?faces-redirect=true";
        }
    }

    /**
     * Helper method to get current HTTP session
     */
    private HttpSession getCurrentSession() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            return attrs.getRequest().getSession(false);
        } catch (Exception e) {
            log.debug("Could not get current session", e);
            return null;
        }
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.cron;

import ch.plaintext.PlaintextCron;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.bus.ExecutionScope;
import it.sauronsoftware.cron4j.Predictor;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.StopWatch;
import org.ocpsoft.prettytime.PrettyTime;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
public abstract class SuperCron implements PlaintextCron, InitializingBean, ApplicationContextAware, BeanNameAware, Runnable {

    /**
     * Principal-Name des technischen Cron-Users. Entspricht dem SYSTEM-User, den
     * {@code PlaintextSecurityImpl.getUser()} auch ohne Authentifizierung meldet — so bleiben
     * Auditing ({@code @CreatedBy}/{@code @LastModifiedBy}) und Log-Ausgaben konsistent.
     */
    public static final String SYSTEM_USER = "SYSTEM";

    /** Marker-Rolle, an der Code einen Cron-/System-Lauf erkennen kann. */
    public static final String ROLE_SYSTEM = "ROLE_SYSTEM";

    /**
     * Baut für einen Cron-Lauf einen definierten {@link SecurityContext} auf:
     * technischer {@value #SYSTEM_USER}-User mit {@value #ROLE_SYSTEM} und der
     * {@code PROPERTY_MANDAT_<mandant>}-Authority des Ziel-Mandanten. Damit liefert
     * {@code PlaintextSecurityHolder.getMandat()}/{@code getUser()} in Background-Jobs
     * (cron4j-Threads laufen OHNE Request-/Session-Kontext) sinnvolle Werte statt
     * {@code NO_AUTH}-Defaults.
     *
     * <p>WICHTIG: Der bestehende Context wird NIE in-place mutiert (Muster wie im
     * {@code McpBearerTokenFilter}): es wird ein frischer Context gesetzt; der Aufrufer
     * ({@link #run()}) restauriert bzw. cleart ihn im {@code finally}.</p>
     *
     * @param mandant Ziel-Mandant des Laufs (z. B. {@code "global"} oder ein echter Mandant)
     */
    private void loginMandat(String mandant) {
        loginMandatUser(mandant, SYSTEM_USER);
    }

    /**
     * Wie {@link #loginMandat(String)}, aber mit einem konkreten Benutzer als Principal statt
     * {@value #SYSTEM_USER} — genutzt für {@link ExecutionScope#PERSOENLICH}-Läufe
     * ({@link #runProPersoenlich()}), damit {@code PlaintextSecurityHolder.getUser()} innerhalb des
     * Laufs den richtigen Benutzer liefert.
     *
     * @param mandant Ziel-Mandant des Laufs
     * @param userId  Ziel-Benutzer; {@code null}/leer fällt auf {@value #SYSTEM_USER} zurück
     */
    private void loginMandatUser(String mandant, String userId) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        authorities.add(new SimpleGrantedAuthority(ROLE_SYSTEM));
        if (mandant != null && !mandant.isBlank()) {
            authorities.add(new SimpleGrantedAuthority("PROPERTY_MANDAT_" + mandant.toLowerCase(Locale.ROOT)));
        }
        String principal = userId != null && !userId.isBlank() ? userId : SYSTEM_USER;
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContext cronContext = SecurityContextHolder.createEmptyContext();
        cronContext.setAuthentication(auth);
        SecurityContextHolder.setContext(cronContext);
    }

    private Object state;

    public void setState(Object obj) {
        this.state = obj;
    }

    public Object getState() {
        return state;
    }

    private boolean running = false;

    private Date last;

    private int seconds;

    private StopWatch watch = new StopWatch();

    private String myName;

    private ApplicationContext context;

    private boolean startup = false;

    private boolean enabled = false;

    private boolean global = false;
    @Getter
    @Setter
    private String mandant = "n/a";
    @Getter
    private int counter;
    private String cron = "0 0 * * *";

    @Getter
    @Setter
    private Class<? extends PlaintextCron> originalBeanClass;

    public String getCronString() {
        return cron;
    }

    public void setCronString(String cron) {
        this.cron = cron;
    }

    public Date getNextRun() {
        try {
            String cronString = getCronString();
            if (cronString == null || cronString.trim().isEmpty()) {
                log.warn("Cron '{}' has no cron string set", getName());
                return null;
            }
            Predictor pr = new Predictor(cronString);
            return pr.nextMatchingDate();
        } catch (Exception e) {
            log.error("Failed to calculate next run for cron '{}' (mandant: {}) with pattern '{}': {}",
                    getName(), getMandant(), getCronString(), e.getMessage());
            return null;
        }
    }

    @Override
    public boolean isGlobal() {
        return global;
    }

    public int getPercente() {
        if (watch.isStarted()) {
            watch.split();
            int zeit = (int) watch.getSplitTime() / 1000;
            int sec = 1;
            if (seconds < 1) {
                sec = 1;
            }

            if (zeit < 1) {
                zeit = 1;
            }

            int p = (sec * 100) / zeit;

            log.debug("seconds: " + sec + " / " + zeit + " = " + p);

            return 100 - p;
        }
        return 0;
    }

    public Date getLastRun() {
        return last;
    }

    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public String getDisplayName() {
        // Default implementation returns the simple class name
        // Subclasses can override this to provide a more descriptive name
        return getClass().getSimpleName();
    }

    @Override
    public String getDefaultCronExpression() {
        // Default implementation returns daily at midnight
        // Subclasses can override this to provide their own default schedule
        return "0 0 * * *";
    }

    public String getBeanName() {
        return myName;
    }

    public int getLastSeconds() {
        return seconds;
    }

    public boolean isRunning() {
        return running;
    }

    public void start() {
        watch = new StopWatch();
        watch.start();
        running = true;
    }

    public String getWann() {
        try {
            if (getCronString() == null || getCronString().trim().isEmpty()) {
                return "-";
            }

            PrettyTime t = new PrettyTime(new Date());
            t.setLocale(Locale.GERMAN);

            Predictor pr = new Predictor(getCronString());
            Date d = pr.nextMatchingDate();

            return t.format(d);
        } catch (Exception e) {
            log.error("Failed to calculate next run time for cron '{}' (mandant: {}) with pattern '{}': {}",
                    getName(), getMandant(), getCronString(), e.getMessage());
            return "ERROR: Invalid pattern";
        }
    }


    public void ende() {
        last = new Date();
        running = false;
        seconds = (int) watch.getTime(TimeUnit.SECONDS);
        watch.reset();
        counter++;

        // Sync execution statistics to the persistent entity
        syncToEntity();
    }

    /**
     * Synchronizes the execution statistics (counter, lastRun, lastSeconds)
     * from this SuperCron instance to the CronConfigEntity.
     */
    private void syncToEntity() {
        if (state instanceof CronConfigEntity) {
            CronConfigEntity entity = (CronConfigEntity) state;
            entity.setCounter(counter);
            entity.setLastRun(last);
            entity.setLastSeconds(seconds);
        }
    }

    /**
     * Loads the execution statistics from the CronConfigEntity into this SuperCron instance.
     * This should be called during initialization to restore the state from the database.
     */
    public void loadFromEntity() {
        if (state instanceof CronConfigEntity) {
            CronConfigEntity entity = (CronConfigEntity) state;
            if (entity.getCounter() != null) {
                this.counter = entity.getCounter();
            }
            if (entity.getLastRun() != null) {
                this.last = entity.getLastRun();
            }
            if (entity.getLastSeconds() != null) {
                this.seconds = entity.getLastSeconds();
            }
        }
    }


    public void run() {
        log.info(">>> Starting cron '{}' for mandant '{}' (scope: {})", getName(), getMandant(), getScope());
        start();

        // Vorherigen Context merken (auf gepoolten Threads könnte theoretisch einer liegen),
        // dann frischen System-Context für diesen Lauf setzen. Restore/Clear IMMER im finally —
        // auch im Exception-Pfad darf keine System-Authentication auf dem Thread zurückbleiben.
        SecurityContext previous = SecurityContextHolder.getContext();
        boolean previousHadAuthentication = previous.getAuthentication() != null;

        try {
            if (getScope() == ExecutionScope.PERSOENLICH) {
                runProPersoenlich();
            } else {
                loginMandat(getMandant());
                run(getMandant());
            }
            log.info(">>> Cron '{}' for mandant '{}' completed successfully", getName(), getMandant());
        } catch (Exception e) {
            log.error(">>> ERROR in cron '{}' for mandant '{}'", getName(), getMandant(), e);
            throw e;
        } finally {
            if (previousHadAuthentication) {
                SecurityContextHolder.setContext(previous);
            } else {
                SecurityContextHolder.clearContext();
            }
            ende();
            log.info(">>> Cron '{}' ended. Counter: {}, Last run: {}, Duration: {}s",
                    getName(), getCounter(), getLastRun(), getLastSeconds());
        }
    }

    /**
     * {@link ExecutionScope#PERSOENLICH}-Ausführung: einmal je aktivem Benutzer des Mandanten
     * ({@link PlaintextSecurity#getUsersForMandat(String)}), mit Kontext = Mandant + jeweiliger
     * Benutzer. Fehler-Isolation je Benutzer (try/catch + {@code log.warn}) — ein Benutzer-Fehler
     * bricht weder die restlichen Benutzer noch den Gesamtlauf; die Zeilen-/Schedule-Ebene bleibt
     * dieselbe wie bei {@link ExecutionScope#MANDAT} (genau eine Config-Zeile je Mandant, siehe
     * {@link CronController#createCronsMap()} — keine Zeilen-Explosion pro Benutzer).
     */
    private void runProPersoenlich() {
        PlaintextSecurity security = context.getBean(PlaintextSecurity.class);
        for (String userId : security.getUsersForMandat(getMandant())) {
            loginMandatUser(getMandant(), userId);
            try {
                run(getMandant(), userId);
            } catch (Exception e) {
                log.warn(">>> ERROR in cron '{}' for mandant '{}' user '{}': {}",
                        getName(), getMandant(), userId, e.toString());
            }
        }
    }

    @Override
    public abstract void run(String mandant);


    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.context = applicationContext;
    }

    @Override
    public void setBeanName(String s) {
        this.myName = s;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (this.context.isSingleton(this.myName)) {
            throw new RuntimeException("Bean CANNOT be singleton");
        }
    }

}

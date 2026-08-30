/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.cron;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.bus.ExecutionScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperCronTest {

    @Mock
    private ApplicationContext applicationContext;

    private TestCron cron;

    /** Concrete subclass for testing the abstract SuperCron. */
    private static class TestCron extends SuperCron {
        private boolean executed = false;
        private String executedMandant;
        private boolean shouldThrow = false;

        @Override
        public void run(String mandant) {
            if (shouldThrow) {
                throw new RuntimeException("Cron failed");
            }
            executed = true;
            executedMandant = mandant;
        }
    }

    /** Concrete subclass with scope PERSOENLICH, for the run(mandant,userId) iteration. */
    private static class PersoenlichTestCron extends SuperCron {
        private final List<String> aufgerufeneUser = new ArrayList<>();
        private final List<String> kontextNamenBeimAufruf = new ArrayList<>();
        private String userWoWirft;

        @Override
        public ExecutionScope getScope() {
            return ExecutionScope.PERSOENLICH;
        }

        @Override
        public void run(String mandant) {
            throw new AssertionError("PERSOENLICH-Cron sollte run(mandant,user) nutzen, nicht run(mandant)");
        }

        @Override
        public void run(String mandant, String userId) {
            kontextNamenBeimAufruf.add(SecurityContextHolder.getContext().getAuthentication().getName());
            if (userId.equals(userWoWirft)) {
                throw new RuntimeException("boom " + userId);
            }
            aufgerufeneUser.add(userId);
        }
    }

    @BeforeEach
    void setUp() {
        cron = new TestCron();
        cron.setBeanName("testCronBean");
        cron.setApplicationContext(applicationContext);
    }

    // --- Basic getters/setters ---

    @Test
    void getNameReturnsSimpleClassName() {
        assertThat(cron.getName()).isEqualTo("TestCron");
    }

    @Test
    void getDisplayNameReturnsSimpleClassNameByDefault() {
        assertThat(cron.getDisplayName()).isEqualTo("TestCron");
    }

    @Test
    void getDefaultCronExpressionReturnsDailyMidnight() {
        assertThat(cron.getDefaultCronExpression()).isEqualTo("0 0 * * *");
    }

    @Test
    void getBeanNameReturnsSetValue() {
        assertThat(cron.getBeanName()).isEqualTo("testCronBean");
    }

    @Test
    void setBeanNameUpdatesValue() {
        cron.setBeanName("newName");
        assertThat(cron.getBeanName()).isEqualTo("newName");
    }

    @Test
    void isGlobalReturnsFalseByDefault() {
        assertThat(cron.isGlobal()).isFalse();
    }

    @Test
    void mandantDefaultsToNA() {
        assertThat(cron.getMandant()).isEqualTo("n/a");
    }

    @Test
    void setMandantUpdatesValue() {
        cron.setMandant("testMandat");
        assertThat(cron.getMandant()).isEqualTo("testMandat");
    }

    @Test
    void counterStartsAtZero() {
        assertThat(cron.getCounter()).isZero();
    }

    @Test
    void getCronStringReturnsDefault() {
        assertThat(cron.getCronString()).isEqualTo("0 0 * * *");
    }

    @Test
    void setCronStringUpdatesValue() {
        cron.setCronString("30 6 * * 1");
        assertThat(cron.getCronString()).isEqualTo("30 6 * * 1");
    }

    // --- State management ---

    @Test
    void stateIsNullByDefault() {
        assertThat(cron.getState()).isNull();
    }

    @Test
    void setStateAndGetState() {
        Object obj = new Object();
        cron.setState(obj);
        assertThat(cron.getState()).isSameAs(obj);
    }

    // --- Running state ---

    @Test
    void isRunningReturnsFalseByDefault() {
        assertThat(cron.isRunning()).isFalse();
    }

    @Test
    void startSetsRunningToTrue() {
        cron.start();
        assertThat(cron.isRunning()).isTrue();
    }

    @Test
    void lastRunIsNullByDefault() {
        assertThat(cron.getLastRun()).isNull();
    }

    @Test
    void lastSecondsIsZeroByDefault() {
        assertThat(cron.getLastSeconds()).isZero();
    }

    // --- run() lifecycle ---

    @Test
    void runExecutesCronLogicAndTracksState() {
        cron.setMandant("mandatA");

        cron.run();

        assertThat(cron.executed).isTrue();
        assertThat(cron.executedMandant).isEqualTo("mandatA");
        assertThat(cron.isRunning()).isFalse();
        assertThat(cron.getCounter()).isEqualTo(1);
        assertThat(cron.getLastRun()).isNotNull();
    }

    @Test
    void runIncrementsCounterOnEachExecution() {
        cron.run();
        cron.run();
        cron.run();

        assertThat(cron.getCounter()).isEqualTo(3);
    }

    @Test
    void runSetsLastRunDate() {
        Date before = new Date();
        cron.run();
        Date after = new Date();

        assertThat(cron.getLastRun()).isAfterOrEqualTo(before);
        assertThat(cron.getLastRun()).isBeforeOrEqualTo(after);
    }

    @Test
    void runStopsWatchAndRecordsSeconds() {
        cron.run();

        // Duration should be very small for a no-op cron
        assertThat(cron.getLastSeconds()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void runStillCallsEndeWhenExceptionThrown() {
        cron.shouldThrow = true;

        assertThatThrownBy(() -> cron.run())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Cron failed");

        // ende() should still have been called (in finally block)
        assertThat(cron.isRunning()).isFalse();
        assertThat(cron.getCounter()).isEqualTo(1);
        assertThat(cron.getLastRun()).isNotNull();
    }

    // --- ende() and syncToEntity ---

    @Test
    void endeSyncsToEntityWhenStateIsCronConfigEntity() {
        CronConfigEntity entity = new CronConfigEntity();
        cron.setState(entity);

        cron.start();
        cron.run("test");
        cron.ende();

        assertThat(entity.getCounter()).isEqualTo(1);
        assertThat(entity.getLastRun()).isNotNull();
        assertThat(entity.getLastSeconds()).isNotNull();
    }

    @Test
    void endeDoesNotFailWhenStateIsNotCronConfigEntity() {
        cron.setState("not an entity");

        cron.start();
        cron.run("test");
        cron.ende();

        // Should not throw, counter still increments
        assertThat(cron.getCounter()).isEqualTo(1);
    }

    @Test
    void endeDoesNotFailWhenStateIsNull() {
        cron.setState(null);

        cron.start();
        cron.run("test");
        cron.ende();

        assertThat(cron.getCounter()).isEqualTo(1);
    }

    // --- loadFromEntity ---

    @Test
    void loadFromEntityRestoresStateFromCronConfigEntity() {
        CronConfigEntity entity = new CronConfigEntity();
        Date savedDate = new Date(1000000L);
        entity.setCounter(42);
        entity.setLastRun(savedDate);
        entity.setLastSeconds(120);
        cron.setState(entity);

        cron.loadFromEntity();

        assertThat(cron.getCounter()).isEqualTo(42);
        assertThat(cron.getLastRun()).isEqualTo(savedDate);
        assertThat(cron.getLastSeconds()).isEqualTo(120);
    }

    @Test
    void loadFromEntityHandlesNullFields() {
        CronConfigEntity entity = new CronConfigEntity();
        entity.setCounter(null);
        entity.setLastRun(null);
        entity.setLastSeconds(null);
        cron.setState(entity);

        cron.loadFromEntity();

        // Should not overwrite with null - counter stays at default 0
        assertThat(cron.getCounter()).isZero();
    }

    @Test
    void loadFromEntityDoesNothingWhenStateIsNotEntity() {
        cron.setState("not an entity");

        cron.loadFromEntity();

        assertThat(cron.getCounter()).isZero();
    }

    @Test
    void loadFromEntityDoesNothingWhenStateIsNull() {
        cron.setState(null);

        cron.loadFromEntity();

        assertThat(cron.getCounter()).isZero();
    }

    // --- getNextRun ---

    @Test
    void getNextRunReturnsDateForValidCronExpression() {
        cron.setCronString("0 0 * * *");

        Date nextRun = cron.getNextRun();

        assertThat(nextRun).isNotNull();
        assertThat(nextRun).isAfter(new Date());
    }

    @Test
    void getNextRunReturnsNullForEmptyCronString() {
        cron.setCronString("");

        assertThat(cron.getNextRun()).isNull();
    }

    @Test
    void getNextRunReturnsNullForNullCronString() {
        cron.setCronString(null);

        assertThat(cron.getNextRun()).isNull();
    }

    @Test
    void getNextRunReturnsNullForInvalidCronString() {
        cron.setCronString("invalid cron");

        assertThat(cron.getNextRun()).isNull();
    }

    // --- getWann ---

    @Test
    void getWannReturnsStringForValidCron() {
        cron.setCronString("0 0 * * *");

        String wann = cron.getWann();

        assertThat(wann).isNotNull();
        assertThat(wann).isNotEmpty();
        assertThat(wann).isNotEqualTo("-");
    }

    @Test
    void getWannReturnsDashForEmptyCronString() {
        cron.setCronString("");

        assertThat(cron.getWann()).isEqualTo("-");
    }

    @Test
    void getWannReturnsDashForNullCronString() {
        cron.setCronString(null);

        assertThat(cron.getWann()).isEqualTo("-");
    }

    @Test
    void getWannReturnsErrorForInvalidCronString() {
        cron.setCronString("not valid");

        assertThat(cron.getWann()).isEqualTo("ERROR: Invalid pattern");
    }

    // --- getPercente ---

    @Test
    void getPercenteReturnsZeroWhenNotStarted() {
        assertThat(cron.getPercente()).isZero();
    }

    @Test
    void getPercenteReturnsValueWhenRunning() {
        cron.start();
        // The watch is started, getPercente should not throw
        int percente = cron.getPercente();
        assertThat(percente).isGreaterThanOrEqualTo(0);
    }

    // --- afterPropertiesSet ---

    @Test
    void afterPropertiesSetThrowsWhenBeanIsSingleton() {
        when(applicationContext.isSingleton("testCronBean")).thenReturn(true);

        assertThatThrownBy(() -> cron.afterPropertiesSet())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Bean CANNOT be singleton");
    }

    @Test
    void afterPropertiesSetDoesNotThrowWhenBeanIsPrototype() throws Exception {
        when(applicationContext.isSingleton("testCronBean")).thenReturn(false);

        // Card 968 (java:S2699): "should not throw" was not a side note here but the WHOLE
        // statement of the method - it throws for a singleton and does nothing otherwise. That is
        // exactly what belongs in an assertion rather than in a comment.
        assertDoesNotThrow(() -> cron.afterPropertiesSet());
    }

    // --- originalBeanClass ---

    @Test
    void originalBeanClassGetterSetterWork() {
        cron.setOriginalBeanClass(TestCron.class);
        assertThat(cron.getOriginalBeanClass()).isEqualTo(TestCron.class);
    }

    // --- setApplicationContext ---

    @Test
    void setApplicationContextStoresContext() {
        ApplicationContext newCtx = org.mockito.Mockito.mock(ApplicationContext.class);
        cron.setApplicationContext(newCtx);
        // Verify indirectly through afterPropertiesSet
        when(newCtx.isSingleton("testCronBean")).thenReturn(false);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> cron.afterPropertiesSet());
    }

    // --- getScope() (Task 005) ---

    @Test
    void getScopeDefaultsToMandatWhenNotGlobal() {
        assertThat(cron.getScope()).isEqualTo(ExecutionScope.MANDAT);
    }

    // --- run() with ExecutionScope.PERSOENLICH (Task 005) ---

    private PersoenlichTestCron persoenlichCron(List<String> user) {
        PersoenlichTestCron pCron = new PersoenlichTestCron();
        pCron.setBeanName("persoenlichCronBean");
        pCron.setApplicationContext(applicationContext);
        pCron.setMandant("mandatA");
        PlaintextSecurity security = mock(PlaintextSecurity.class);
        when(applicationContext.getBean(PlaintextSecurity.class)).thenReturn(security);
        when(security.getUsersForMandat("mandatA")).thenReturn(user);
        return pCron;
    }

    @Test
    void run_persoenlich_ruftRunProUserAuf() {
        PersoenlichTestCron pCron = persoenlichCron(List.of("alice", "bob"));

        pCron.run();

        assertThat(pCron.aufgerufeneUser).containsExactly("alice", "bob");
    }

    @Test
    void run_persoenlich_setztMandantUndBenutzerAlsKontextJeAufruf() {
        PersoenlichTestCron pCron = persoenlichCron(List.of("alice", "bob"));

        pCron.run();

        assertThat(pCron.kontextNamenBeimAufruf).containsExactly("alice", "bob");
    }

    @Test
    void run_persoenlich_fehlerBeiEinemUser_andereLaufenTrotzdem() {
        PersoenlichTestCron pCron = persoenlichCron(List.of("alice", "bob", "carol"));
        pCron.userWoWirft = "bob";

        pCron.run(); // must NOT throw — error isolation per user

        assertThat(pCron.aufgerufeneUser).containsExactly("alice", "carol");
    }

    @Test
    void run_persoenlich_ohneUser_ruftNiemandenAuf() {
        PersoenlichTestCron pCron = persoenlichCron(List.of());

        pCron.run();

        assertThat(pCron.aufgerufeneUser).isEmpty();
        assertThat(pCron.getCounter()).isEqualTo(1); // ende() ran anyway
    }

    @Test
    void run_persoenlich_restauriertVorherigenSecurityContextDanach() {
        Authentication vorherige = new UsernamePasswordAuthenticationToken("vorheriger-user", null, List.of());
        SecurityContext vorherigerContext = SecurityContextHolder.createEmptyContext();
        vorherigerContext.setAuthentication(vorherige);
        SecurityContextHolder.setContext(vorherigerContext);

        try {
            PersoenlichTestCron pCron = persoenlichCron(List.of("alice"));

            pCron.run();

            assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("vorheriger-user");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void run_persoenlich_ohneVorherigenKontext_wirdAmEndeGecleared() {
        SecurityContextHolder.clearContext();
        PersoenlichTestCron pCron = persoenlichCron(List.of("alice"));

        pCron.run();

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}

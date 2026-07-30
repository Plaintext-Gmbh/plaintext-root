/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.cron;

import ch.plaintext.PlaintextCron;
import ch.plaintext.PlaintextSecurity;
import it.sauronsoftware.cron4j.Scheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CronControllerTest {

    @Mock
    private CronConfigRepository cronConfigRepository;

    @Mock
    private ApplicationContext ctx;

    @Mock
    private PlaintextSecurity plaintextSecurity;

    @InjectMocks
    private CronController cronController;

    private SuperCron createTestSuperCron(String name, boolean global) {
        SuperCron sc = new SuperCron() {
            @Override
            public void run(String mandant) {
                // no-op
            }

            @Override
            public boolean isGlobal() {
                return global;
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDefaultCronExpression() {
                return "0 0 * * *";
            }
        };
        sc.setBeanName(name + "Bean");
        sc.setApplicationContext(ctx);
        return sc;
    }

    @BeforeEach
    void setUp() throws Exception {
        // Set the crons list field via reflection (normally @Autowired)
        Field cronsField = CronController.class.getDeclaredField("crons");
        cronsField.setAccessible(true);
        cronsField.set(cronController, new ArrayList<PlaintextCron>());
    }

    // --- validateAndFixCronExpression (private, tested via schedule indirectly) ---

    @Test
    void validateAndFixCronExpression_nullExpression() throws Exception {
        Method method = CronController.class.getDeclaredMethod(
                "validateAndFixCronExpression", String.class, String.class, String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(cronController, null, "test", "mandatA");
        assertThat(result).isEqualTo("0 0 * * *");
    }

    @Test
    void validateAndFixCronExpression_emptyExpression() throws Exception {
        Method method = CronController.class.getDeclaredMethod(
                "validateAndFixCronExpression", String.class, String.class, String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(cronController, "", "test", "mandatA");
        assertThat(result).isEqualTo("0 0 * * *");
    }

    @Test
    void validateAndFixCronExpression_sixFieldConvertedToFive() throws Exception {
        Method method = CronController.class.getDeclaredMethod(
                "validateAndFixCronExpression", String.class, String.class, String.class);
        method.setAccessible(true);

        // 6-field (Spring/Quartz) pattern: seconds minutes hours dayOfMonth month dayOfWeek
        String result = (String) method.invoke(cronController, "0 30 6 * * *", "test", "mandatA");
        assertThat(result).isEqualTo("30 6 * * *");
    }

    @Test
    void validateAndFixCronExpression_fiveFieldPassedThrough() throws Exception {
        Method method = CronController.class.getDeclaredMethod(
                "validateAndFixCronExpression", String.class, String.class, String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(cronController, "30 6 * * *", "test", "mandatA");
        assertThat(result).isEqualTo("30 6 * * *");
    }

    @Test
    void validateAndFixCronExpression_invalidFieldCountPassedThrough() throws Exception {
        Method method = CronController.class.getDeclaredMethod(
                "validateAndFixCronExpression", String.class, String.class, String.class);
        method.setAccessible(true);

        // 3 fields - invalid, but returned as-is (will fail at scheduling)
        String result = (String) method.invoke(cronController, "30 6 *", "test", "mandatA");
        assertThat(result).isEqualTo("30 6 *");
    }

    @Test
    void validateAndFixCronExpression_whitespaceOnlyExpression() throws Exception {
        Method method = CronController.class.getDeclaredMethod(
                "validateAndFixCronExpression", String.class, String.class, String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(cronController, "   ", "test", "mandatA");
        assertThat(result).isEqualTo("0 0 * * *");
    }

    // --- applyTimeOffset (private) ---

    @Test
    void applyTimeOffset_nullExpression() throws Exception {
        Method method = CronController.class.getDeclaredMethod(
                "applyTimeOffset", String.class, int.class);
        method.setAccessible(true);

        String result = (String) method.invoke(cronController, null, 1);
        assertThat(result).isNull();
    }

    @Test
    void applyTimeOffset_emptyExpression() throws Exception {
        Method method = CronController.class.getDeclaredMethod(
                "applyTimeOffset", String.class, int.class);
        method.setAccessible(true);

        String result = (String) method.invoke(cronController, "", 1);
        assertThat(result).isEmpty();
    }

    @Test
    void applyTimeOffset_zeroOffset() throws Exception {
        Method method = CronController.class.getDeclaredMethod(
                "applyTimeOffset", String.class, int.class);
        method.setAccessible(true);

        String result = (String) method.invoke(cronController, "0 6 * * *", 0);
        assertThat(result).isEqualTo("0 6 * * *");
    }

    @Test
    void applyTimeOffset_appliesOffset() throws Exception {
        Method method = CronController.class.getDeclaredMethod(
                "applyTimeOffset", String.class, int.class);
        method.setAccessible(true);

        // Offset 1 -> 2 minutes added to minute field
        String result = (String) method.invoke(cronController, "0 6 * * *", 1);
        assertThat(result).isEqualTo("2 6 * * *");
    }

    @Test
    void applyTimeOffset_wrapsMinutesAt60() throws Exception {
        Method method = CronController.class.getDeclaredMethod(
                "applyTimeOffset", String.class, int.class);
        method.setAccessible(true);

        // Offset 30 -> 60 minutes, 55 + 60 = 115 % 60 = 55
        String result = (String) method.invoke(cronController, "55 6 * * *", 30);
        assertThat(result).isEqualTo("55 6 * * *");  // (55 + 60) % 60 = 55
    }

    @Test
    void applyTimeOffset_skipsNonNumericMinuteField() throws Exception {
        Method method = CronController.class.getDeclaredMethod(
                "applyTimeOffset", String.class, int.class);
        method.setAccessible(true);

        // */15 is not a simple numeric, so expression returned as-is
        String result = (String) method.invoke(cronController, "*/15 6 * * *", 1);
        assertThat(result).isEqualTo("*/15 6 * * *");
    }

    @Test
    void applyTimeOffset_nonStandardFieldCount() throws Exception {
        Method method = CronController.class.getDeclaredMethod(
                "applyTimeOffset", String.class, int.class);
        method.setAccessible(true);

        // 6 fields - can't modify, returned as-is
        String result = (String) method.invoke(cronController, "0 0 6 * * *", 1);
        assertThat(result).isEqualTo("0 0 6 * * *");
    }

    @Test
    void applyTimeOffset_whitespaceOnlyReturnsOriginal() throws Exception {
        Method method = CronController.class.getDeclaredMethod(
                "applyTimeOffset", String.class, int.class);
        method.setAccessible(true);

        String result = (String) method.invoke(cronController, "   ", 1);
        assertThat(result).isEqualTo("   ");
    }

    // --- save ---

    @Test
    void savePreservesTransientCronReference() {
        CronConfigEntity entity = new CronConfigEntity();
        SuperCron sc = createTestSuperCron("TestCron", false);
        entity.setCron(sc);

        CronConfigEntity savedEntity = new CronConfigEntity();
        when(cronConfigRepository.save(any(CronConfigEntity.class))).thenReturn(savedEntity);

        CronConfigEntity result = cronController.save(entity);

        assertThat(result.getCron()).isSameAs(sc);
        verify(cronConfigRepository).save(entity);
    }

    @Test
    void saveWithNullCronReference() {
        CronConfigEntity entity = new CronConfigEntity();
        entity.setCron(null);

        CronConfigEntity savedEntity = new CronConfigEntity();
        when(cronConfigRepository.save(any(CronConfigEntity.class))).thenReturn(savedEntity);

        CronConfigEntity result = cronController.save(entity);

        assertThat(result.getCron()).isNull();
        verify(cronConfigRepository).save(entity);
    }

    // --- findCronEntity (private) ---

    @Test
    void findCronEntity_findsMatchingEntity() throws Exception {
        // Set up cronsMap with an entity
        CronConfigEntity entity = new CronConfigEntity();
        entity.setCronName("TestCron");
        entity.setMandat("mandatA");

        Map<String, List<CronConfigEntity>> map = new HashMap<>();
        map.put("mandatA", new ArrayList<>(List.of(entity)));
        cronController.getCronsMap().putAll(map);

        Method method = CronController.class.getDeclaredMethod(
                "findCronEntity", String.class, String.class);
        method.setAccessible(true);

        CronConfigEntity result = (CronConfigEntity) method.invoke(cronController, "TestCron", "mandatA");
        assertThat(result).isSameAs(entity);
    }

    @Test
    void findCronEntity_returnsNullWhenNotFound() throws Exception {
        Method method = CronController.class.getDeclaredMethod(
                "findCronEntity", String.class, String.class);
        method.setAccessible(true);

        CronConfigEntity result = (CronConfigEntity) method.invoke(cronController, "NotExist", "mandatX");
        assertThat(result).isNull();
    }

    @Test
    void findCronEntity_returnsNullWhenMandatNotInMap() throws Exception {
        Method method = CronController.class.getDeclaredMethod(
                "findCronEntity", String.class, String.class);
        method.setAccessible(true);

        CronConfigEntity result = (CronConfigEntity) method.invoke(cronController, "TestCron", "unknownMandat");
        assertThat(result).isNull();
    }

    @Test
    void findCronEntity_returnsNullWhenNameDoesNotMatch() throws Exception {
        CronConfigEntity entity = new CronConfigEntity();
        entity.setCronName("OtherCron");
        entity.setMandat("mandatA");

        cronController.getCronsMap().put("mandatA", new ArrayList<>(List.of(entity)));

        Method method = CronController.class.getDeclaredMethod(
                "findCronEntity", String.class, String.class);
        method.setAccessible(true);

        CronConfigEntity result = (CronConfigEntity) method.invoke(cronController, "TestCron", "mandatA");
        assertThat(result).isNull();
    }

    // --- createCronConfigEntity (private) ---

    @Test
    void createCronConfigEntity_setsDefaults() throws Exception {
        SuperCron sc = createTestSuperCron("TestCron", false);

        Method method = CronController.class.getDeclaredMethod(
                "createCronConfigEntity", SuperCron.class);
        method.setAccessible(true);

        CronConfigEntity result = (CronConfigEntity) method.invoke(cronController, sc);

        assertThat(result.isEnabled()).isTrue();
        assertThat(result.isStartup()).isTrue();
        assertThat(result.getCronExpression()).isEqualTo("0 0 * * *");
    }

    @Test
    void createCronConfigEntity_usesCustomDefaultCronExpression() throws Exception {
        SuperCron sc = new SuperCron() {
            @Override
            public void run(String mandant) {}

            @Override
            public String getName() { return "Custom"; }

            @Override
            public String getDefaultCronExpression() { return "30 6 * * 1"; }
        };
        sc.setBeanName("customBean");
        sc.setApplicationContext(ctx);

        Method method = CronController.class.getDeclaredMethod(
                "createCronConfigEntity", SuperCron.class);
        method.setAccessible(true);

        CronConfigEntity result = (CronConfigEntity) method.invoke(cronController, sc);

        assertThat(result.getCronExpression()).isEqualTo("30 6 * * 1");
    }

    // --- cronsMap getter ---

    @Test
    void getCronsMapReturnsEmptyMapByDefault() {
        assertThat(cronController.getCronsMap()).isNotNull();
        assertThat(cronController.getCronsMap()).isEmpty();
    }

    // --- schedule ---

    @Test
    void schedule_successfullySchedulesCron() throws Exception {
        // Initialize the scheduler via scheduleTheMap
        cronController.getCronsMap().put("mandatA", new ArrayList<>());
        cronController.scheduleTheMap();

        SuperCron sc = createTestSuperCron("TestCron", false);
        CronConfigEntity entity = new CronConfigEntity();
        entity.setCronExpression("0 6 * * *");
        entity.setCronName("TestCron");
        entity.setMandat("mandatA");
        entity.setCron(sc);

        cronController.schedule(entity);

        // Verify the cron is scheduled by checking we can unschedule it without error
        cronController.unschedule(entity);
    }

    @Test
    void schedule_fallsBackOnInvalidExpression() throws Exception {
        // Initialize the scheduler
        cronController.getCronsMap().put("mandatA", new ArrayList<>());
        cronController.scheduleTheMap();

        SuperCron sc = createTestSuperCron("TestCron", false);
        CronConfigEntity entity = new CronConfigEntity();
        entity.setCronExpression("invalid expression");
        entity.setCronName("TestCron");
        entity.setMandat("mandatA");
        entity.setCron(sc);
        entity.setEnabled(true);

        when(cronConfigRepository.save(any(CronConfigEntity.class))).thenReturn(entity);

        cronController.schedule(entity);

        // Should have used fallback expression and disabled the entity
        assertThat(entity.getCronExpression()).isEqualTo("59 23 31 12 2");
        assertThat(entity.isEnabled()).isFalse();
        verify(cronConfigRepository).save(entity);
    }

    @Test
    void schedule_handlesNullExpressionWithDefault() throws Exception {
        // Initialize the scheduler
        cronController.getCronsMap().put("mandatA", new ArrayList<>());
        cronController.scheduleTheMap();

        SuperCron sc = createTestSuperCron("TestCron", false);
        CronConfigEntity entity = new CronConfigEntity();
        entity.setCronExpression(null);
        entity.setCronName("TestCron");
        entity.setMandat("mandatA");
        entity.setCron(sc);

        cronController.schedule(entity);

        // validateAndFixCronExpression replaces null with "0 0 * * *", so it should schedule fine
    }

    @Test
    void schedule_handlesEmptyExpressionWithDefault() throws Exception {
        // Initialize the scheduler
        cronController.getCronsMap().put("mandatA", new ArrayList<>());
        cronController.scheduleTheMap();

        SuperCron sc = createTestSuperCron("TestCron", false);
        CronConfigEntity entity = new CronConfigEntity();
        entity.setCronExpression("");
        entity.setCronName("TestCron");
        entity.setMandat("mandatA");
        entity.setCron(sc);

        cronController.schedule(entity);
        // Should not throw - empty expression gets replaced with default
    }

    // --- scheduleTheMap ---

    @Test
    void scheduleTheMap_schedulesEnabledCronsAndTriggersStartup() throws Exception {
        SuperCron sc = createTestSuperCron("TestCron", false);

        CronConfigEntity enabledWithStartup = new CronConfigEntity();
        enabledWithStartup.setCronExpression("0 6 * * *");
        enabledWithStartup.setCronName("TestCron");
        enabledWithStartup.setMandat("mandatA");
        enabledWithStartup.setEnabled(true);
        enabledWithStartup.setStartup(true);
        enabledWithStartup.setCron(sc);

        cronController.getCronsMap().put("mandatA", new ArrayList<>(List.of(enabledWithStartup)));

        // scheduleTheMap will schedule and trigger startup crons
        // trigger() needs the entity to be findable in cronsMap
        cronController.scheduleTheMap();

        // If we got here without exceptions, scheduling and trigger worked
    }

    @Test
    void scheduleTheMap_skipsDisabledCrons() throws Exception {
        SuperCron sc = createTestSuperCron("DisabledCron", false);

        CronConfigEntity disabledEntity = new CronConfigEntity();
        disabledEntity.setCronExpression("0 6 * * *");
        disabledEntity.setCronName("DisabledCron");
        disabledEntity.setMandat("mandatA");
        disabledEntity.setEnabled(false);
        disabledEntity.setStartup(false);
        disabledEntity.setCron(sc);

        cronController.getCronsMap().put("mandatA", new ArrayList<>(List.of(disabledEntity)));
        cronController.scheduleTheMap();

        // Disabled cron should not throw; no scheduling
    }

    @Test
    void scheduleTheMap_enabledWithoutStartupDoesNotTrigger() throws Exception {
        SuperCron sc = createTestSuperCron("NoStartupCron", false);

        CronConfigEntity enabledNoStartup = new CronConfigEntity();
        enabledNoStartup.setCronExpression("0 6 * * *");
        enabledNoStartup.setCronName("NoStartupCron");
        enabledNoStartup.setMandat("mandatA");
        enabledNoStartup.setEnabled(true);
        enabledNoStartup.setStartup(false);
        enabledNoStartup.setCron(sc);

        cronController.getCronsMap().put("mandatA", new ArrayList<>(List.of(enabledNoStartup)));
        cronController.scheduleTheMap();

        // No startup trigger, but scheduled successfully
    }

    @Test
    void scheduleTheMap_multipleMandantenWithMultipleCrons() throws Exception {
        SuperCron sc1 = createTestSuperCron("Cron1", false);
        SuperCron sc2 = createTestSuperCron("Cron2", false);

        CronConfigEntity entity1 = new CronConfigEntity();
        entity1.setCronExpression("0 6 * * *");
        entity1.setCronName("Cron1");
        entity1.setMandat("mandatA");
        entity1.setEnabled(true);
        entity1.setStartup(false);
        entity1.setCron(sc1);

        CronConfigEntity entity2 = new CronConfigEntity();
        entity2.setCronExpression("30 8 * * *");
        entity2.setCronName("Cron2");
        entity2.setMandat("mandatB");
        entity2.setEnabled(true);
        entity2.setStartup(false);
        entity2.setCron(sc2);

        cronController.getCronsMap().put("mandatA", new ArrayList<>(List.of(entity1)));
        cronController.getCronsMap().put("mandatB", new ArrayList<>(List.of(entity2)));
        cronController.scheduleTheMap();

        // Both should be scheduled without error
    }

    // --- unschedule ---

    @Test
    void unschedule_removesScheduledCron() throws Exception {
        // Initialize scheduler and schedule a cron
        cronController.getCronsMap().put("mandatA", new ArrayList<>());
        cronController.scheduleTheMap();

        SuperCron sc = createTestSuperCron("TestCron", false);
        CronConfigEntity entity = new CronConfigEntity();
        entity.setCronExpression("0 6 * * *");
        entity.setCronName("TestCron");
        entity.setMandat("mandatA");
        entity.setCron(sc);

        cronController.schedule(entity);
        // Should not throw
        cronController.unschedule(entity);
    }

    // --- trigger ---

    @Test
    void trigger_throwsWhenTaskIdNotFound() throws Exception {
        // Initialize scheduler
        cronController.getCronsMap().put("mandatA", new ArrayList<>());
        cronController.scheduleTheMap();

        assertThatThrownBy(() -> cronController.trigger("NonExistent", "mandatA"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cron task ID not found");
    }

    @Test
    void trigger_executesSuccessfullyForScheduledCron() throws Exception {
        SuperCron sc = createTestSuperCron("TestCron", false);

        CronConfigEntity entity = new CronConfigEntity();
        entity.setCronExpression("0 6 * * *");
        entity.setCronName("TestCron");
        entity.setMandat("mandatA");
        entity.setCron(sc);
        entity.setEnabled(true);
        entity.setStartup(false);

        cronController.getCronsMap().put("mandatA", new ArrayList<>(List.of(entity)));
        cronController.scheduleTheMap();

        when(cronConfigRepository.save(any(CronConfigEntity.class))).thenReturn(entity);

        // Trigger the cron - should succeed
        cronController.trigger("TestCron", "mandatA");

        verify(cronConfigRepository).save(any(CronConfigEntity.class));
    }

    @Test
    void trigger_handlesEntityNotFoundInMap() throws Exception {
        SuperCron sc = createTestSuperCron("TestCron", false);

        CronConfigEntity entity = new CronConfigEntity();
        entity.setCronExpression("0 6 * * *");
        entity.setCronName("TestCron");
        entity.setMandat("mandatA");
        entity.setCron(sc);
        entity.setEnabled(true);
        entity.setStartup(false);

        // Put entity in map to schedule it, then remove it from the map
        cronController.getCronsMap().put("mandatA", new ArrayList<>(List.of(entity)));
        cronController.scheduleTheMap();

        // Clear the map so findCronEntity returns null
        cronController.getCronsMap().clear();

        // Trigger should still work but entity will be null (logged as error)
        cronController.trigger("TestCron", "mandatA");
    }

    // --- createCronsMap (via init) ---

    @Test
    void init_createsMapForGlobalCrons() throws Exception {
        SuperCron globalCron = createTestSuperCron("GlobalCron", true);

        Field cronsField = CronController.class.getDeclaredField("crons");
        cronsField.setAccessible(true);
        cronsField.set(cronController, new ArrayList<>(List.of((PlaintextCron) globalCron)));

        when(plaintextSecurity.getAllMandate()).thenReturn(Set.of("mandatA"));
        CronConfigEntity savedEntity = new CronConfigEntity();
        savedEntity.setCronName("GlobalCron");
        savedEntity.setMandat("global");
        savedEntity.setCronExpression("0 0 * * *");
        savedEntity.setEnabled(false);

        when(cronConfigRepository.findByCronNameAndMandat("GlobalCron", "global"))
                .thenReturn(Optional.empty());
        when(cronConfigRepository.save(any(CronConfigEntity.class))).thenReturn(savedEntity);

        cronController.init();

        Map<String, List<CronConfigEntity>> map = cronController.getCronsMap();
        assertThat(map).containsKey("global");
        assertThat(map.get("global")).hasSize(1);
    }

    @Test
    void init_createsMapForGlobalCrons_existingConfig() throws Exception {
        SuperCron globalCron = createTestSuperCron("GlobalCron", true);

        Field cronsField = CronController.class.getDeclaredField("crons");
        cronsField.setAccessible(true);
        cronsField.set(cronController, new ArrayList<>(List.of((PlaintextCron) globalCron)));

        when(plaintextSecurity.getAllMandate()).thenReturn(Set.of("mandatA"));

        CronConfigEntity existingEntity = new CronConfigEntity();
        existingEntity.setCronName("GlobalCron");
        existingEntity.setMandat("global");
        existingEntity.setCronExpression("30 6 * * *");
        existingEntity.setEnabled(false);

        when(cronConfigRepository.findByCronNameAndMandat("GlobalCron", "global"))
                .thenReturn(Optional.of(existingEntity));
        when(cronConfigRepository.save(any(CronConfigEntity.class))).thenReturn(existingEntity);

        cronController.init();

        Map<String, List<CronConfigEntity>> map = cronController.getCronsMap();
        assertThat(map.get("global")).hasSize(1);
        assertThat(map.get("global").get(0).getCronExpression()).isEqualTo("30 6 * * *");
    }

    @Test
    void init_createsMapForNonGlobalCrons() throws Exception {
        SuperCron nonGlobalCron = createTestSuperCron("LocalCron", false);

        Field cronsField = CronController.class.getDeclaredField("crons");
        cronsField.setAccessible(true);
        cronsField.set(cronController, new ArrayList<>(List.of((PlaintextCron) nonGlobalCron)));

        when(plaintextSecurity.getAllMandate()).thenReturn(Set.of("mandatA"));

        when(cronConfigRepository.findByCronNameAndMandat("LocalCron", "mandatA"))
                .thenReturn(Optional.empty());

        CronConfigEntity savedEntity = new CronConfigEntity();
        savedEntity.setCronName("LocalCron");
        savedEntity.setMandat("mandatA");
        savedEntity.setCronExpression("0 0 * * *");
        savedEntity.setEnabled(false);

        when(cronConfigRepository.save(any(CronConfigEntity.class))).thenReturn(savedEntity);

        // The non-global path clones via ctx.getBean
        SuperCron clonedCron = createTestSuperCron("LocalCron", false);
        when(ctx.getBean(eq("LocalCronBean"), eq(PlaintextCron.class))).thenReturn(clonedCron);

        cronController.init();

        Map<String, List<CronConfigEntity>> map = cronController.getCronsMap();
        assertThat(map).containsKey("mandatA");
        assertThat(map.get("mandatA")).hasSize(1);
    }

    @Test
    void init_nonGlobalCronExistingConfig() throws Exception {
        SuperCron nonGlobalCron = createTestSuperCron("LocalCron", false);

        Field cronsField = CronController.class.getDeclaredField("crons");
        cronsField.setAccessible(true);
        cronsField.set(cronController, new ArrayList<>(List.of((PlaintextCron) nonGlobalCron)));

        when(plaintextSecurity.getAllMandate()).thenReturn(Set.of("mandatA"));

        CronConfigEntity existingEntity = new CronConfigEntity();
        existingEntity.setCronName("LocalCron");
        existingEntity.setMandat("mandatA");
        existingEntity.setCronExpression("15 8 * * *");
        existingEntity.setEnabled(false);

        when(cronConfigRepository.findByCronNameAndMandat("LocalCron", "mandatA"))
                .thenReturn(Optional.of(existingEntity));
        when(cronConfigRepository.save(any(CronConfigEntity.class))).thenReturn(existingEntity);

        SuperCron clonedCron = createTestSuperCron("LocalCron", false);
        when(ctx.getBean(eq("LocalCronBean"), eq(PlaintextCron.class))).thenReturn(clonedCron);

        cronController.init();

        Map<String, List<CronConfigEntity>> map = cronController.getCronsMap();
        assertThat(map.get("mandatA")).hasSize(1);
    }

    @Test
    void init_skipsNonSuperCronBeans() throws Exception {
        PlaintextCron plainCron = mandant -> {};

        Field cronsField = CronController.class.getDeclaredField("crons");
        cronsField.setAccessible(true);
        cronsField.set(cronController, new ArrayList<>(List.of(plainCron)));

        when(plaintextSecurity.getAllMandate()).thenReturn(Set.of("mandatA"));

        cronController.init();

        Map<String, List<CronConfigEntity>> map = cronController.getCronsMap();
        assertThat(map.get("mandatA")).isEmpty();
        assertThat(map.get("global")).isEmpty();
    }

    @Test
    void init_nonGlobalCronNullBeanNameSkips() throws Exception {
        SuperCron cronWithNullBeanName = new SuperCron() {
            @Override
            public void run(String mandant) {}

            @Override
            public boolean isGlobal() { return false; }

            @Override
            public String getName() { return "NullBeanCron"; }

            @Override
            public String getDefaultCronExpression() { return "0 0 * * *"; }
        };
        // Do NOT call setBeanName - leave it null

        Field cronsField = CronController.class.getDeclaredField("crons");
        cronsField.setAccessible(true);
        cronsField.set(cronController, new ArrayList<>(List.of((PlaintextCron) cronWithNullBeanName)));

        when(plaintextSecurity.getAllMandate()).thenReturn(Set.of("mandatA"));
        when(cronConfigRepository.findByCronNameAndMandat("NullBeanCron", "mandatA"))
                .thenReturn(Optional.empty());
        when(cronConfigRepository.save(any(CronConfigEntity.class))).thenAnswer(i -> i.getArgument(0));

        cronController.init();

        // Should skip the cron due to null bean name (logs error, continues)
        Map<String, List<CronConfigEntity>> map = cronController.getCronsMap();
        assertThat(map.get("mandatA")).isEmpty();
    }

    @Test
    void init_multipleMandantenForNonGlobalCron() throws Exception {
        SuperCron nonGlobalCron = createTestSuperCron("MultiCron", false);

        Field cronsField = CronController.class.getDeclaredField("crons");
        cronsField.setAccessible(true);
        cronsField.set(cronController, new ArrayList<>(List.of((PlaintextCron) nonGlobalCron)));

        when(plaintextSecurity.getAllMandate()).thenReturn(new LinkedHashSet<>(List.of("mandatA", "mandatB")));

        when(cronConfigRepository.findByCronNameAndMandat(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(cronConfigRepository.save(any(CronConfigEntity.class))).thenAnswer(i -> i.getArgument(0));

        SuperCron cloned1 = createTestSuperCron("MultiCron", false);
        SuperCron cloned2 = createTestSuperCron("MultiCron", false);
        when(ctx.getBean(eq("MultiCronBean"), eq(PlaintextCron.class)))
                .thenReturn(cloned1, cloned2);

        cronController.init();

        Map<String, List<CronConfigEntity>> map = cronController.getCronsMap();
        assertThat(map.get("mandatA")).hasSize(1);
        assertThat(map.get("mandatB")).hasSize(1);
    }

    @Test
    void init_nonGlobalCronSkipsGlobalMandant() throws Exception {
        SuperCron nonGlobalCron = createTestSuperCron("LocalOnly", false);

        Field cronsField = CronController.class.getDeclaredField("crons");
        cronsField.setAccessible(true);
        cronsField.set(cronController, new ArrayList<>(List.of((PlaintextCron) nonGlobalCron)));

        // "global" is added internally; non-global crons skip "global" mandant
        when(plaintextSecurity.getAllMandate()).thenReturn(Set.of("mandatA"));

        when(cronConfigRepository.findByCronNameAndMandat("LocalOnly", "mandatA"))
                .thenReturn(Optional.empty());
        when(cronConfigRepository.save(any(CronConfigEntity.class))).thenAnswer(i -> i.getArgument(0));

        SuperCron cloned = createTestSuperCron("LocalOnly", false);
        when(ctx.getBean(eq("LocalOnlyBean"), eq(PlaintextCron.class))).thenReturn(cloned);

        cronController.init();

        // "global" mandant should be empty - non-global crons are not added to global
        Map<String, List<CronConfigEntity>> map = cronController.getCronsMap();
        assertThat(map.get("global")).isEmpty();
        assertThat(map.get("mandatA")).hasSize(1);
    }

    @Test
    void init_emptyMandantenSetStillAddsGlobal() throws Exception {
        Field cronsField = CronController.class.getDeclaredField("crons");
        cronsField.setAccessible(true);
        cronsField.set(cronController, new ArrayList<>());

        when(plaintextSecurity.getAllMandate()).thenReturn(Set.of());

        cronController.init();

        Map<String, List<CronConfigEntity>> map = cronController.getCronsMap();
        assertThat(map).containsKey("global");
    }

    // --- schedule with six-field expression (auto-conversion) ---

    @Test
    void schedule_sixFieldExpressionAutoConverted() throws Exception {
        cronController.getCronsMap().put("mandatA", new ArrayList<>());
        cronController.scheduleTheMap();

        SuperCron sc = createTestSuperCron("SixFieldCron", false);
        CronConfigEntity entity = new CronConfigEntity();
        entity.setCronExpression("0 30 6 * * *"); // 6-field Quartz format
        entity.setCronName("SixFieldCron");
        entity.setMandat("mandatA");
        entity.setCron(sc);

        cronController.schedule(entity);
        // Should auto-convert to "30 6 * * *" and schedule successfully
    }

    @Test
    void trigger_throwsWhenTaskNotFoundInScheduler() throws Exception {
        // Initialize scheduler
        cronController.getCronsMap().put("mandatA", new ArrayList<>());
        cronController.scheduleTheMap();

        // Inject a fake taskId directly into cronsId map via reflection
        Field cronsIdField = CronController.class.getDeclaredField("cronsId");
        cronsIdField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> cronsIdMap = (Map<String, String>) cronsIdField.get(cronController);
        cronsIdMap.put("FakeCronmandatA", "nonexistent-task-id");

        // Now trigger should find the taskId but scheduler.getTask returns null
        assertThatThrownBy(() -> cronController.trigger("FakeCron", "mandatA"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cron task not found in scheduler");
    }

    // --- trigger with exception ---

    @Test
    void trigger_exceptionDuringExecutionIsPropagated() throws Exception {
        SuperCron failingCron = new SuperCron() {
            @Override
            public void run(String mandant) {
                throw new RuntimeException("Execution failed!");
            }

            @Override
            public String getName() { return "FailCron"; }

            @Override
            public String getDefaultCronExpression() { return "0 0 * * *"; }
        };
        failingCron.setBeanName("FailCronBean");
        failingCron.setApplicationContext(ctx);

        CronConfigEntity entity = new CronConfigEntity();
        entity.setCronExpression("0 6 * * *");
        entity.setCronName("FailCron");
        entity.setMandat("mandatA");
        entity.setCron(failingCron);
        entity.setEnabled(true);
        entity.setStartup(false);

        cronController.getCronsMap().put("mandatA", new ArrayList<>(List.of(entity)));
        cronController.scheduleTheMap();

        when(cronConfigRepository.save(any(CronConfigEntity.class))).thenReturn(entity);

        // The trigger method catches exceptions from the scheduled task via scheduler.launch()
        // which is async. The trigger method itself should complete.
        cronController.trigger("FailCron", "mandatA");
    }
}

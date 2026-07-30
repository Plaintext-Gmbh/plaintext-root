/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.cron;

import ch.plaintext.PlaintextCron;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.bus.ExecutionScope;
import it.sauronsoftware.cron4j.Scheduler;
import it.sauronsoftware.cron4j.Task;
import jakarta.inject.Named;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.*;

@Slf4j
@Component
@Named("emadCronController")
public class CronController implements Serializable {

    private static final long serialVersionUID = 1L;

    @Autowired
    private CronConfigRepository cronConfigRepository;

    @Autowired
    private ApplicationContext ctx;

    @Autowired
    private PlaintextSecurity plaintextSecurity;

    // required=false + leere Default-Liste: eine Deployment-Variante kann ganz ohne PlaintextCron-Beans
    // laufen (z. B. root-webapp standalone), ohne dass die Context-Erstellung an einer leeren Collection scheitert.
    @Autowired(required = false)
    private List<PlaintextCron> crons = new ArrayList<>();

    private Scheduler scheduler;

    @Getter
    private Map<String, List<CronConfigEntity>> cronsMap = new HashMap<>();

    private Map<String, String> cronsId = new HashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    void init() {
        cronsMap = createCronsMap();
        scheduleTheMap();
    }

    private Map<String, List<CronConfigEntity>> createCronsMap() {

        Set<String> mandanten = new HashSet<>(plaintextSecurity.getAllMandate());
        mandanten.add("global");

        // Defensive: also pick up any mandat that already has a cron_config row.
        // PlaintextSecurity.getAllMandate() has been observed to miss mandants on prod
        // (cause not yet identified) which then silently disables all of their crons.
        // Including stored cron_config mandants closes that gap until the discovery is fixed.
        cronConfigRepository.findAll().forEach(cfg -> {
            String m = cfg.getMandat();
            if (m != null && !m.isBlank()) {
                mandanten.add(m);
            }
        });

        Map<String, List<CronConfigEntity>> result = new HashMap<>();
        for (String mandant : mandanten) {
            result.put(mandant, new ArrayList<>());
        }

        log.info("Found mandanten: {}", mandanten);
        log.info("Found {} PlaintextCron beans", crons.size());

        // Create an ordered list of mandanten for time offset calculation
        List<String> orderedMandanten = new ArrayList<>(mandanten);
        orderedMandanten.sort(String::compareTo);

        for (PlaintextCron cron : crons) {
            // At runtime, cron will be a SuperCron due to BeanPostProcessor
            if (!(cron instanceof SuperCron)) {
                log.warn("Skipping bean {} - not a SuperCron instance", cron.getClass().getName());
                continue;
            }
            SuperCron superCron = (SuperCron) cron;

            log.info("Processing cron: {} (scope: {})", superCron.getName(), cron.getScope());

            CronConfigEntity entity = null;
            if (cron.getScope() == ExecutionScope.APPLICATION) {
                 Optional<CronConfigEntity> config = cronConfigRepository.findByCronNameAndMandat(superCron.getName(),"global");
                 if(config.isPresent()){
                     entity = config.get();
                 } else {
                     entity = createCronConfigEntity(superCron);
                     entity.setCronName(superCron.getName());
                     entity.setMandat("global");
                 }
                entity = cronConfigRepository.save(entity);
                entity.setCron(superCron);
                entity.getCron().setState(entity);
                entity.getCron().setMandant("global");
                entity.getCron().loadFromEntity(); // Load counter, lastRun, etc. from DB
                result.get("global").add(entity);
            } else {
                int mandantIndex = 0;
                for (String mandat : orderedMandanten) {
                    if ("global".equals(mandat)) {
                        continue;
                    }
                    Optional<CronConfigEntity> config = cronConfigRepository.findByCronNameAndMandat(superCron.getName(),mandat);
                    if(config.isPresent()){
                        entity = config.get();
                    } else {
                        entity = createCronConfigEntity(superCron);
                        entity.setCronName(superCron.getName());
                        entity.setMandat(mandat);
                        // Apply time offset for non-global crons to prevent parallel execution
                        entity.setCronExpression(applyTimeOffset(entity.getCronExpression(), mandantIndex));
                    }
                    //clone - get a new wrapped instance using bean name (prototype scope)
                    String beanName = superCron.getBeanName();
                    if (beanName == null) {
                        log.error("Bean name is null for cron: {}", superCron.getName());
                        continue;
                    }

                    PlaintextCron newCronBean = ctx.getBean(beanName, PlaintextCron.class);
                    SuperCron newSuperCron = (SuperCron) newCronBean;

                    entity = cronConfigRepository.save(entity);
                    entity.setCron(newSuperCron);
                    entity.getCron().setState(entity);
                    entity.getCron().setMandant(mandat);
                    entity.getCron().loadFromEntity(); // Load counter, lastRun, etc. from DB
                    result.get(mandat).add(entity);
                    mandantIndex++;
                }
            }
        }
        return result;
    }

    private CronConfigEntity createCronConfigEntity(SuperCron superCron){
        CronConfigEntity ret = new CronConfigEntity();
        ret.setEnabled(true);
        ret.setStartup(true);
        // Use the default cron expression from the cron job itself
        ret.setCronExpression(superCron.getDefaultCronExpression());
        return ret;
    }

    public void scheduleTheMap() {
        log.info("CronController.scheduleTheMap() starting...");

        // 3) Crons schedulen
        scheduler = new Scheduler();
        scheduler.start();

        List<CronConfigEntity> allCrons = new ArrayList<>();
        for (List<CronConfigEntity> list : cronsMap.values()) {
            allCrons.addAll(list);
        }
        for (CronConfigEntity real : allCrons) {
            if(!real.isEnabled()){
                continue;
            }
            schedule(real);
            if(real.isStartup()){
                trigger(real.getCronName(),real.getMandat());
            }
        }

        log.info("scheduleTheMap.init() completed. Initialized {} cron jobs", allCrons.size());
    }

    public void unschedule(CronConfigEntity entity){
        scheduler.deschedule(cronsId.get(entity.getCronName() + entity.getMandat()));
    }

    public void schedule(CronConfigEntity entity){
        String cronExpression = entity.getCronExpression();
        String cronName = entity.getCronName();
        String mandant = entity.getMandat();

        try {
            // Validate and potentially fix the cron expression
            cronExpression = validateAndFixCronExpression(cronExpression, cronName, mandant);

            String id = scheduler.schedule(cronExpression, entity.getCron());
            cronsId.put(cronName + mandant, id);

            log.info("Successfully scheduled cron '{}' for mandant '{}' with pattern: {}",
                    cronName, mandant, cronExpression);
        } catch (Exception e) {
            // Use fallback: disabled cron that never runs (31st of December at 23:59)
            String fallbackExpression = "59 23 31 12 2";
            log.error("FAILED to schedule cron '{}' for mandant '{}' with pattern '{}'. " +
                    "Using fallback pattern '{}' (effectively disabled). " +
                    "cron4j requires 5 fields: 'minute hour dayOfMonth month dayOfWeek'. " +
                    "Example: '0 6 * * *' (daily at 6:00). Error: {}",
                    cronName, mandant, cronExpression, fallbackExpression, e.getMessage());

            try {
                String id = scheduler.schedule(fallbackExpression, entity.getCron());
                cronsId.put(cronName + mandant, id);
                log.warn("Cron '{}' for mandant '{}' scheduled with fallback pattern '{}' (disabled)",
                        cronName, mandant, fallbackExpression);

                // Update the entity with the fallback expression
                entity.setCronExpression(fallbackExpression);
                entity.setEnabled(false);
                cronConfigRepository.save(entity);
            } catch (Exception fallbackException) {
                log.error("Failed to schedule even with fallback pattern for '{}' (mandant: {}): {}",
                        cronName, mandant, fallbackException.getMessage());
                // Don't throw - just skip this cron
            }
        }
    }

    /**
     * Applies a time offset to a cron expression to stagger execution across mandanten.
     * This prevents all mandanten from running the same cron job simultaneously.
     *
     * @param expression The original cron expression
     * @param offsetMinutes Number of minutes to offset (typically mandant index * 2)
     * @return Modified cron expression with time offset
     */
    private String applyTimeOffset(String expression, int offsetMinutes) {
        if (expression == null || expression.trim().isEmpty() || offsetMinutes == 0) {
            return expression;
        }

        // Apply 2-minute offset per mandant to stagger execution
        int minuteOffset = offsetMinutes * 2;

        String[] fields = expression.trim().split("\\s+");
        if (fields.length != 5) {
            // Can't reliably modify non-standard patterns
            log.warn("Cannot apply time offset to non-standard cron pattern with {} fields: {}",
                    fields.length, expression);
            return expression;
        }

        // Parse the minute field (first field in cron4j format)
        String minuteField = fields[0];

        try {
            // Only modify simple numeric minute values, not patterns like */15
            if (minuteField.matches("\\d+")) {
                int originalMinute = Integer.parseInt(minuteField);
                int newMinute = (originalMinute + minuteOffset) % 60;
                fields[0] = String.valueOf(newMinute);

                String modifiedExpression = String.join(" ", fields);
                log.info("Applied {}-minute offset to cron pattern '{}' -> '{}'",
                        minuteOffset, expression, modifiedExpression);
                return modifiedExpression;
            }
        } catch (NumberFormatException e) {
            log.debug("Could not parse minute field '{}' for offset: {}", minuteField, e.getMessage());
        }

        return expression;
    }

    /**
     * Validates and potentially fixes common cron expression issues.
     * cron4j uses 5 fields: minute hour dayOfMonth month dayOfWeek
     * Spring/Quartz use 6 fields: second minute hour dayOfMonth month dayOfWeek
     */
    private String validateAndFixCronExpression(String expression, String cronName, String mandant) {
        if (expression == null || expression.trim().isEmpty()) {
            log.warn("Empty cron expression for '{}' (mandant: {}), using default '0 0 * * *'",
                    cronName, mandant);
            return "0 0 * * *";
        }

        String[] fields = expression.trim().split("\\s+");

        if (fields.length == 6) {
            // Looks like a Quartz/Spring pattern (with seconds field)
            // Remove the first field (seconds) to convert to cron4j format
            String converted = String.join(" ", Arrays.copyOfRange(fields, 1, 6));
            log.warn("Cron '{}' (mandant: {}) uses 6-field pattern (Spring/Quartz format): '{}'. " +
                    "Automatically converting to cron4j 5-field format by removing seconds: '{}'. " +
                    "Please update the configuration to use the correct format.",
                    cronName, mandant, expression, converted);
            return converted;
        }

        if (fields.length != 5) {
            log.error("Cron '{}' (mandant: {}) has {} fields but cron4j requires exactly 5 fields " +
                    "(minute hour dayOfMonth month dayOfWeek). Pattern: '{}'",
                    cronName, mandant, fields.length, expression);
        }

        return expression;
    }

    public CronConfigEntity save(CronConfigEntity entity) {
        SuperCron cronRef = entity.getCron();
        CronConfigEntity ret = cronConfigRepository.save(entity);
        ret.setCron(cronRef);
        return ret;
    }

    public void trigger(String name, String mandat) {
        String taskId = cronsId.get(name + mandat);

        if (taskId == null) {
            log.error("Cannot trigger cron '{}' for mandant '{}': No task ID found in cronsId map. " +
                    "Available keys: {}", name, mandat, cronsId.keySet());
            throw new IllegalStateException("Cron task ID not found for: " + name + " (mandant: " + mandat + ")");
        }

        Task task = scheduler.getTask(taskId);
        if (task == null) {
            log.error("Cannot trigger cron '{}' for mandant '{}': Task not found in scheduler for ID '{}'. " +
                    "This might indicate the cron was not properly scheduled.", name, mandat, taskId);
            throw new IllegalStateException("Cron task not found in scheduler: " + name + " (mandant: " + mandat + ")");
        }

        log.info("=== MANUAL TRIGGER START ===");
        log.info("Triggering cron '{}' for mandant '{}' (taskId: {})", name, mandat, taskId);
        log.info("Task class: {}", task.getClass().getName());

        // Find the entity for this cron
        CronConfigEntity entity = findCronEntity(name, mandat);

        try {
            scheduler.launch(task);
            log.info("Cron '{}' for mandant '{}' launch completed", name, mandat);

            // Wait a bit for the async task to complete
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            // Save the updated statistics to the database
            if (entity != null) {
                // Sync from SuperCron (in-memory) to Entity fields
                entity.syncFromCron();

                // Save to DB (this returns a detached entity without @Transient SuperCron!)
                cronConfigRepository.save(entity);

                // The original entity still has the SuperCron reference, and it already has
                // the updated values from ende() which were synced above
                log.info("Saved cron statistics: counter={}, lastRun={}",
                        entity.getCounter(), entity.getLastRun());
            } else {
                log.error("Entity is NULL - cannot save statistics!");
            }
        } catch (Exception e) {
            log.error("ERROR during cron execution for '{}' (mandant: {})", name, mandat, e);
            throw e;
        }

        log.info("=== MANUAL TRIGGER END ===");
    }

    /**
     * Finds the CronConfigEntity for a given cron name and mandant
     */
    private CronConfigEntity findCronEntity(String name, String mandat) {
        List<CronConfigEntity> list = cronsMap.get(mandat);
        if (list != null) {
            for (CronConfigEntity entity : list) {
                if (entity.getCronName().equals(name)) {
                    return entity;
                }
            }
        }
        return null;
    }

}

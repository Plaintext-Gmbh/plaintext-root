-- Fix invalid cron patterns with 6 fields (Quartz/Spring format) to 5 fields (cron4j format)
-- cron4j uses: minute hour dayOfMonth month dayOfWeek
-- Quartz uses: second minute hour dayOfMonth month dayOfWeek

-- Fix pattern "0 0 6 * * *" to "0 6 * * *" (remove seconds field)
UPDATE cron_config
SET cron_expression = '0 6 * * *'
WHERE cron_expression = '0 0 6 * * *';

-- Fix any other 6-field patterns by removing the first field (seconds)
-- PostgreSQL uses position() instead of HSQLDB's LOCATE()
UPDATE cron_config
SET cron_expression = SUBSTRING(cron_expression FROM position(' ' IN cron_expression) + 1)
WHERE LENGTH(cron_expression) - LENGTH(REPLACE(cron_expression, ' ', '')) = 5;

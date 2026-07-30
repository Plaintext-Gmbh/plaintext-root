-- Fix cron expressions from 6-field Spring/Quartz format to 5-field cron4j format
-- cron4j format: minute hour dayOfMonth month dayOfWeek
-- Spring/Quartz format: second minute hour dayOfMonth month dayOfWeek

-- Fix Geburtstags-Email-Benachrichtigung cron expression
-- "0 0 6 * * *" (6 fields) -> "0 6 * * *" (5 fields)
UPDATE cron_config
SET cron_expression = '0 6 * * *'
WHERE cron_name = 'KontaktEmailAvisTrigger'
AND cron_expression = '0 0 6 * * *';

-- Fix any other 6-field patterns that start with "0 " (seconds field)
-- This removes the first field (seconds) to convert to cron4j format
UPDATE cron_config
SET cron_expression = SUBSTRING(cron_expression, 3)
WHERE cron_expression LIKE '0 %'
AND LENGTH(cron_expression) - LENGTH(REPLACE(cron_expression, ' ', '')) = 5;

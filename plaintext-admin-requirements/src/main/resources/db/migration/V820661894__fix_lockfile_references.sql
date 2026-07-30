-- Update all lockfile references to use a single lockfile without numbers
-- This replaces claude-task-XXXXX.lock with claude-task.lock

UPDATE constraint_template
SET constraints_content = REPLACE(constraints_content, 'claude-task-00001.lock', 'claude-task.lock')
WHERE constraints_content LIKE '%claude-task-00001.lock%';

UPDATE constraint_template
SET constraints_content = REPLACE(constraints_content, 'claude-task-00002.lock', 'claude-task.lock')
WHERE constraints_content LIKE '%claude-task-00002.lock%';

UPDATE constraint_template
SET constraints_content = REPLACE(constraints_content, 'claude-task-00003.lock', 'claude-task.lock')
WHERE constraints_content LIKE '%claude-task-00003.lock%';

UPDATE constraint_template
SET constraints_content = REPLACE(constraints_content, 'claude-task-00004.lock', 'claude-task.lock')
WHERE constraints_content LIKE '%claude-task-00004.lock%';

UPDATE constraint_template
SET constraints_content = REPLACE(constraints_content, 'claude-task-00005.lock', 'claude-task.lock')
WHERE constraints_content LIKE '%claude-task-00005.lock%';

-- Generic replacement for any remaining numbered lockfiles
-- Uses REGEXP_REPLACE if supported by HSQLDB, otherwise manual patterns
UPDATE constraint_template
SET constraints_content = REPLACE(
    REPLACE(
        REPLACE(
            REPLACE(
                REPLACE(
                    REPLACE(
                        REPLACE(
                            REPLACE(
                                REPLACE(
                                    REPLACE(constraints_content,
                                        'claude-task-00006.lock', 'claude-task.lock'),
                                    'claude-task-00007.lock', 'claude-task.lock'),
                                'claude-task-00008.lock', 'claude-task.lock'),
                            'claude-task-00009.lock', 'claude-task.lock'),
                        'claude-task-00010.lock', 'claude-task.lock'),
                    'claude-ack-00001.lock', 'claude-task.lock'),
                'claude-ack-00002.lock', 'claude-task.lock'),
            'claude-ack-00003.lock', 'claude-task.lock'),
        'claude-ack-00004.lock', 'claude-task.lock'),
    'claude-ack-00005.lock', 'claude-task.lock')
WHERE constraints_content LIKE '%claude-%lock%';

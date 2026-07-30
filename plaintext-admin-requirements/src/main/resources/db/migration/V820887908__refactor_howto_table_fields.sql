-- Refactor Howto table to match new requirements
-- Rename 'instructions' to 'text' with 2000 char limit
-- Rename 'description' to 'beispiel' with TEXT type

-- Step 1: Add new columns
ALTER TABLE howto ADD COLUMN text VARCHAR(2000);
ALTER TABLE howto ADD COLUMN beispiel TEXT;

-- Step 2: Migrate data from old columns to new columns
UPDATE howto SET text = SUBSTRING(instructions, 1, 2000) WHERE instructions IS NOT NULL;
UPDATE howto SET beispiel = description WHERE description IS NOT NULL;

-- Step 3: Make text column NOT NULL (after migration)
ALTER TABLE howto ALTER COLUMN text SET NOT NULL;

-- Step 4: Drop old columns
ALTER TABLE howto DROP COLUMN instructions;
ALTER TABLE howto DROP COLUMN description;

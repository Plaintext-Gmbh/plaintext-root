-- Remove menu_name column from anforderung_api_settings table
-- This field is no longer needed as it was only used for UI display

ALTER TABLE anforderung_api_settings DROP COLUMN menu_name;

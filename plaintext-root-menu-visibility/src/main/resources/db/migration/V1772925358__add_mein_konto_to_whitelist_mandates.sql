-- Add "Postkonto | Mein Konto" to all mandates that use whitelist mode and already have "Postkonto" visible
INSERT INTO MANDATE_HIDDEN_MENUS (CONFIG_ID, MENU_TITLE)
SELECT c.ID, 'Postkonto | Mein Konto'
FROM MANDATE_MENU_CONFIG c
WHERE c.IS_WHITELIST_MODE = TRUE
  AND EXISTS (
    SELECT 1 FROM MANDATE_HIDDEN_MENUS m
    WHERE m.CONFIG_ID = c.ID AND m.MENU_TITLE = 'Postkonto'
  )
  AND NOT EXISTS (
    SELECT 1 FROM MANDATE_HIDDEN_MENUS m
    WHERE m.CONFIG_ID = c.ID AND m.MENU_TITLE = 'Postkonto | Mein Konto'
  );

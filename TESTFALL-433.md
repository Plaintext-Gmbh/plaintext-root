# Testfall zu Karte 433

Der zweite Commit dieses PRs traegt `[skip-ci]` in seinem **Body**, nicht in der Betreffzeile.
Beim Squash-Merge zieht GitHub ihn in den Body des Merge-Commits.

**Erwartung:** Der Lauf nach dem Merge deployt trotzdem. Vor dem Fix waere er `skipped`.

Diese Datei kann nach der Abnahme geloescht werden — sie dokumentiert nur, wozu der
zweite Commit da ist.

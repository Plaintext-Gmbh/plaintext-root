/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.page;

import ch.plaintext.boot.security.PageAccessGuardService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The access rule of the two pages root brings itself, home and the overview (card 1387).
 *
 * <p>Until card 1387 both were Facelets, and the page guard held them to the roles of the watch
 * settings through the aliases {@code watch/home} and {@code watch/elemente} ->
 * {@code watch-einstellungen} (card 1245). As pages of the mobile framework they are no views any
 * more, the guard does not see them, and without this rule they would have been open to every
 * signed-in user. The rule is the same one, asked the way every module page asks it: the guard,
 * for the menu page whose roles apply.</p>
 *
 * <p>Fail-closed: a guard that throws means no. An application without a guard bean (the module
 * on its own, unit tests) has no rule to apply, which is the guard's own answer when it is
 * switched off.</p>
 */
@Slf4j
final class WatchUhrZugriff {

    /** The menu page whose roles the watch of root follows. */
    static final String MENUESEITE = "/watch-einstellungen.xhtml";

    private WatchUhrZugriff() {
    }

    static boolean erlaubt(ObjectProvider<PageAccessGuardService> guard) {
        try {
            PageAccessGuardService g = guard == null ? null : guard.getIfAvailable();
            return g == null || g.hasAccessToView(MENUESEITE);
        } catch (Exception e) {
            log.warn("Watch: Zugriffsregel der Uhr nicht auswertbar: {}", e.toString());
            return false;
        }
    }
}

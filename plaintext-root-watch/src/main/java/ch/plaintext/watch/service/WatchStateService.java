/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.service;

import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import ch.plaintext.watch.entity.WatchUserState;
import ch.plaintext.watch.repository.WatchUserStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Remembers per user which watch page they were on, and whether the element gallery is on.
 *
 * <h2>The user always comes from the security context</h2>
 *
 * <p>Never from a request parameter. The watch views sit behind the normal sign-in, so the
 * identity is whatever Spring Security established — the same source as on every other page.
 * An earlier module in this house took the user from a request parameter and thereby let
 * anyone read anyone else's data (card 1195); this one cannot, because there is no parameter
 * to take it from.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WatchStateService {

    private final WatchUserStateRepository repository;

    /** State of the signed-in user, created on first use. Empty if nobody is signed in. */
    @Transactional
    public Optional<WatchUserState> eigenerZustand() {
        String benutzer = PlaintextSecurityHolder.getUser();
        if (benutzer == null || benutzer.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(repository.findByBenutzerAndDeletedFalse(benutzer)
                .orElseGet(() -> {
                    WatchUserState neu = new WatchUserState();
                    neu.setBenutzer(benutzer);
                    neu.setMandat(PlaintextSecurityHolder.getMandat());
                    return repository.save(neu);
                }));
    }

    /**
     * Id of the page last shown, if there is one.
     *
     * <p>Deliberately returns the id and not the page. This service must not know the page
     * registry: the registry collects every {@code WatchPage}, one of them ({@code WatchTestPage})
     * asks this service whether it is switched on, and the circle would close — Spring refused to
     * start the application with
     * {@code BeanCurrentlyInCreationException: watchPageRegistry}. Resolving an id to a page is
     * the job of whoever holds both, and that is the frame bean.</p>
     */
    @Transactional
    public Optional<String> gemerkteSeitenId() {
        return eigenerZustand().map(WatchUserState::getAktuelleSeite).filter(id -> !id.isBlank());
    }

    /**
     * Remember the page.
     *
     * <p>Writes only when the value actually changes — the watch view calls this on every
     * render, and an unconditional save would put a row update behind every screen refresh.</p>
     */
    @Transactional
    public void merkeSeite(String seitenId) {
        if (seitenId == null || seitenId.isBlank()) {
            return;
        }
        eigenerZustand().ifPresent(z -> {
            if (!seitenId.equals(z.getAktuelleSeite())) {
                z.setAktuelleSeite(seitenId);
                repository.save(z);
                log.debug("Watch: Position von {} auf {} gemerkt", z.getBenutzer(), seitenId);
            }
        });
    }

    /** Whether the element gallery is switched on for the signed-in user. */
    public boolean testseiteAktiv() {
        return eigenerZustand().map(WatchUserState::isTestseiteAktiv).orElse(false);
    }

    @Transactional
    public void setzeTestseite(boolean aktiv) {
        eigenerZustand().ifPresent(z -> {
            z.setTestseiteAktiv(aktiv);
            repository.save(z);
        });
    }
}

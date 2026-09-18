/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.service;

import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import ch.plaintext.watch.entity.WatchUserState;
import ch.plaintext.watch.page.WatchPage;
import ch.plaintext.watch.page.WatchPageRegistry;
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
 * <p>Never from a request parameter. The watch views live under {@code /nosec/} and are reached
 * with a token in the URL; that token is validated by {@code JwtTokenService} and the resulting
 * identity is what ends up here. An earlier module in this house took the user from a request
 * parameter and thereby let anyone read anyone else's data — see card 1195.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WatchStateService {

    private final WatchUserStateRepository repository;
    private final WatchPageRegistry registry;

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

    /** The page to show: the remembered one, or the first available. */
    @Transactional
    public Optional<WatchPage> aktuelleSeite() {
        return eigenerZustand()
                .map(WatchUserState::getAktuelleSeite)
                .flatMap(registry::byId)
                .filter(WatchPage::available)
                .or(registry::erste);
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

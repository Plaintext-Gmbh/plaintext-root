/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.service;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.anforderungen.entity.Anforderung;
import ch.plaintext.anforderungen.repository.AnforderungRepository;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Named("anforderungService")
@Slf4j
public class AnforderungService {

    private final AnforderungRepository repository;
    private final PlaintextSecurity security;

    public AnforderungService(AnforderungRepository repository, PlaintextSecurity security) {
        this.repository = repository;
        this.security = security;
    }

    public List<Anforderung> getAllAnforderungen(String mandat) {
        return repository.findByMandatOrderByCreatedDateDesc(mandat);
    }

    public List<Anforderung> getAllAnforderungenForCurrentUser() {
        String mandat = getCurrentMandat();
        return getAllAnforderungen(mandat);
    }

    public List<Anforderung> getByStatus(String mandat, String status) {
        return repository.findByMandatAndStatus(mandat, status);
    }

    public List<Anforderung> getByPriority(String mandat, String priority) {
        return repository.findByMandatAndPriority(mandat, priority);
    }

    public List<Anforderung> getCreatedByMe(String username) {
        return repository.findByErsteller(username);
    }

    /**
     * Loads a requirement of the <b>current tenant</b> (card 1360, HB1).
     *
     * <p>Until 30.09.2026 this was a plain {@code findById}: the detail pages
     * ({@code anforderungdetail.xhtml?id=}, {@code claudesummary.xhtml?id=}) showed and edited any
     * requirement whose id was typed into the address bar, including those of other tenants. A
     * foreign id now yields {@link Optional#empty()} — the page shows "not found", exactly like an
     * id that does not exist, so the answer does not reveal whether the id is taken elsewhere.
     */
    public Optional<Anforderung> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findByIdAndMandat(id, getCurrentMandat());
    }

    @Transactional
    public Anforderung save(Anforderung anforderung) {
        if (anforderung.getId() == null) {
            anforderung.setErsteller(security.getUser());
        }
        if ("ERLEDIGT".equals(anforderung.getStatus()) && anforderung.getErledigtDatum() == null) {
            anforderung.setErledigtDatum(LocalDateTime.now());
        }
        return repository.save(anforderung);
    }

    /** Deletes a requirement of the current tenant; a foreign or unknown id is ignored (card 1360, HB1). */
    @Transactional
    public void delete(Long id) {
        findById(id).ifPresentOrElse(a -> {
            repository.delete(a);
            log.info("Deleted anforderung: id={}", id);
        }, () -> log.warn("Anforderung {} nicht geloescht: nicht im eigenen Mandanten", id));
    }

    public long countByStatus(String mandat, String status) {
        return repository.countByMandatAndStatus(mandat, status);
    }

    private String getCurrentMandat() {
        String mandat = security.getMandat();
        if (mandat == null || "NO_AUTH".equals(mandat) || "NO_USER".equals(mandat) || "ERROR".equals(mandat)) {
            throw new IllegalStateException("Cannot access anforderungen - invalid mandat: " + mandat);
        }
        return mandat;
    }
}

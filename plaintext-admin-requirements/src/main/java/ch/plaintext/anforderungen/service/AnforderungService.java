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

    public Optional<Anforderung> findById(Long id) {
        return repository.findById(id);
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

    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
        log.info("Deleted anforderung: id={}", id);
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

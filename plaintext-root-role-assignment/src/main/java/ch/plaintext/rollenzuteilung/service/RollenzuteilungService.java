/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.rollenzuteilung.service;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.rollenzuteilung.entity.Rollenzuteilung;
import ch.plaintext.rollenzuteilung.repository.RollenzuteilungRepository;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@Named("rollenzuteilungService")
@Slf4j
public class RollenzuteilungService {

    private final RollenzuteilungRepository repository;
    private final PlaintextSecurity security;

    public RollenzuteilungService(RollenzuteilungRepository repository, PlaintextSecurity security) {
        this.repository = repository;
        this.security = security;
    }

    public List<Rollenzuteilung> getAllRollenzuteilungen(String mandat) {
        return repository.findByMandat(mandat);
    }

    public List<Rollenzuteilung> getRollenzuteilungenForUser(String username, String mandat) {
        return repository.findByUsernameAndMandat(username, mandat);
    }

    public List<String> getActiveRolesForUser(String username, String mandat) {
        return repository.findActiveRolesByUsernameAndMandat(username, mandat);
    }

    public List<String> getAllUsers(String mandat) {
        return repository.findAllUsernamesByMandat(mandat);
    }

    @Transactional
    public Rollenzuteilung save(Rollenzuteilung rollenzuteilung) {
        return repository.save(rollenzuteilung);
    }

    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
        log.info("Deleted rollenzuteilung: id={}", id);
    }

    @Transactional
    public void deleteByUsernameAndMandatAndRole(String username, String mandat, String roleName) {
        repository.deleteByUsernameAndMandatAndRoleName(username, mandat, roleName);
        log.info("Deleted rollenzuteilung: username={}, mandat={}, role={}", username, mandat, roleName);
    }

    public Optional<Rollenzuteilung> findByUsernameAndMandatAndRole(String username, String mandat, String roleName) {
        return repository.findByUsernameAndMandatAndRoleName(username, mandat, roleName);
    }

    public List<Rollenzuteilung> getAllForCurrentMandat() {
        return getAllRollenzuteilungen(security.getMandat());
    }

    public List<Rollenzuteilung> getAllRollenzuteilungenForCurrentUser() {
        if (security.ifGranted("ROLE_ROOT")) {
            return repository.findAll();
        } else {
            return getAllForCurrentMandat();
        }
    }
}

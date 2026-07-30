/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.rollenzuteilung.repository;

import ch.plaintext.rollenzuteilung.entity.Rollenzuteilung;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RollenzuteilungRepository extends JpaRepository<Rollenzuteilung, Long> {

    List<Rollenzuteilung> findByUsername(String username);

    List<Rollenzuteilung> findByMandat(String mandat);

    List<Rollenzuteilung> findByUsernameAndMandat(String username, String mandat);

    List<Rollenzuteilung> findByRoleName(String roleName);

    Optional<Rollenzuteilung> findByUsernameAndMandatAndRoleName(String username, String mandat, String roleName);

    @Query("SELECT DISTINCT r.roleName FROM Rollenzuteilung r WHERE r.username = :username AND r.mandat = :mandat AND r.active = true")
    List<String> findActiveRolesByUsernameAndMandat(String username, String mandat);

    @Query("SELECT DISTINCT r.username FROM Rollenzuteilung r WHERE r.mandat = :mandat")
    List<String> findAllUsernamesByMandat(String mandat);

    void deleteByUsernameAndMandatAndRoleName(String username, String mandat, String roleName);
}

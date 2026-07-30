/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.repository;

import ch.plaintext.settings.entity.Setting;
import ch.plaintext.settings.entity.SettingId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SettingRepository extends JpaRepository<Setting, SettingId> {

    Optional<Setting> findByKeyAndMandat(String key, String mandat);

    List<Setting> findByMandat(String mandat);

    List<Setting> findByMandatOrderByKeyAsc(String mandat);

    boolean existsByKeyAndMandat(String key, String mandat);

    void deleteByKeyAndMandat(String key, String mandat);

    @Query("SELECT DISTINCT s.key FROM Setting s WHERE s.mandat = :mandat")
    List<String> findAllKeysByMandat(String mandat);

    @Query("SELECT s FROM Setting s WHERE s.key LIKE :keyPrefix AND s.mandat = :mandat ORDER BY s.key ASC")
    List<Setting> findByKeyPrefixAndMandat(String keyPrefix, String mandat);

    @Query("SELECT DISTINCT SUBSTRING(s.key, 1, LOCATE('.', s.key) - 1) FROM Setting s WHERE s.mandat = :mandat AND LOCATE('.', s.key) > 0 UNION SELECT s.key FROM Setting s WHERE s.mandat = :mandat AND LOCATE('.', s.key) = 0")
    List<String> findRootKeys(String mandat);
}

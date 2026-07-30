/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.notifications.repository;

import ch.plaintext.notifications.entity.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByEmpfaengerUsernameAndDeletedFalseOrderByCreatedDateDesc(String username, Pageable pageable);

    long countByEmpfaengerUsernameAndGelesenAmIsNullAndDeletedFalse(String username);

    List<Notification> findByEmpfaengerUsernameAndGelesenAmIsNullAndDeletedFalse(String username);

    List<Notification> findByGelesenAmIsNotNullAndGelesenAmBefore(LocalDateTime cutoff);
}

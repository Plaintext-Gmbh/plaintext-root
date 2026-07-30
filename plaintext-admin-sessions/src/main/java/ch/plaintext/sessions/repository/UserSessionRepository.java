/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.repository;

import ch.plaintext.sessions.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    List<UserSession> findBySessionId(String sessionId);

    @Query("SELECT u FROM UserSession u WHERE u.sessionId = :sessionId ORDER BY u.id DESC")
    List<UserSession> findAllBySessionIdOrderByIdDesc(@Param("sessionId") String sessionId);

    List<UserSession> findByUserIdAndActive(Long userId, Boolean active);

    List<UserSession> findAllByActive(Boolean active);

    List<UserSession> findByMandatAndActive(String mandat, Boolean active);

    @Modifying
    @Query(value = "INSERT INTO user_session (user_id, username, session_id, mandat, user_agent, login_time, last_activity_time, active, created_date, last_modified_date) " +
        "VALUES (:userId, :username, :sessionId, :mandat, :userAgent, :now, :now, true, :now, :now) " +
        "ON CONFLICT (session_id) DO UPDATE SET last_activity_time = :now, last_modified_date = :now",
        nativeQuery = true)
    void upsertSession(@Param("userId") Long userId,
                       @Param("username") String username,
                       @Param("sessionId") String sessionId,
                       @Param("mandat") String mandat,
                       @Param("userAgent") String userAgent,
                       @Param("now") LocalDateTime now);
}

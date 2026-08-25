/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Schreibt den Sitzungs-Audit-Eintrag <b>ausserhalb</b> des Request-Threads.
 *
 * <p><b>Warum eine eigene Bean (Karte 968, Sonar {@code java:S6809}).</b> Im
 * {@code SessionTrackingFilter} stand {@code @Async} an einer Methode, die der Filter per
 * {@code this} aufrief. Ein Selbstaufruf geht am Spring-Proxy vorbei — die Annotation war
 * wirkungslos, das Schreiben lief auf dem Request-Thread. Der Kommentar daneben behauptete das
 * Gegenteil („asynchronously to avoid performance impact").
 *
 * <p><b>Warum das nicht einfach durch einen Selbst-Injekt zu heilen war.</b> Die alte Methode las
 * drei Dinge, die am Thread bzw. am Request haengen: {@code SecurityContextHolder}, den
 * {@code HttpServletRequest} und {@link ch.plaintext.PlaintextSecurity}. Waere sie wirklich
 * asynchron gelaufen, waere der {@code SecurityContextHolder} auf dem Pool-Thread leer gewesen —
 * die Aufzeichnung haette <i>stillschweigend ganz aufgehoert</i>. Der Selbstaufruf war also
 * versehentlich tragend. Deshalb wird alles Thread-Gebundene im Filter gelesen und nur der
 * DB-Schreibvorgang hierher gereicht; {@link SessionAuditServiceImpl#updateOrCreate} nimmt
 * ohnehin ausschliesslich Parameter und liest keinen Thread-Zustand.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionAuditWriter {

    private final SessionAuditServiceImpl sessionAuditService;

    /**
     * @param authentication im Request-Thread eingesammelt — hier wird der
     *                       {@code SecurityContextHolder} bewusst NICHT befragt
     */
    @Async
    public void schreibe(Long userId, String sessionId, Authentication authentication, String userAgent) {
        try {
            sessionAuditService.updateOrCreate(userId, sessionId, authentication, userAgent);
        } catch (Exception e) {
            // Eine misslungene Aufzeichnung darf den Request nicht beruehren - der ist zu diesem
            // Zeitpunkt ohnehin laengst beantwortet.
            log.debug("Session tracking error (non-critical): {}", e.getMessage());
        }
    }
}

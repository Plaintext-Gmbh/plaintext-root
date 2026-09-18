/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.entity;

import ch.plaintext.framework.SuperModel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * What the watch view remembers per user: which page they were on, and whether the element
 * gallery is switched on.
 *
 * <p>Kept in the database rather than in the session on purpose: the watch view is opened by
 * tapping a link, often on a different device than the one used before. A session would lose
 * the position exactly when it matters.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Entity
@Table(name = "watch_user_state")
@Getter
@Setter
public class WatchUserState extends SuperModel {

    /** Login of the user this state belongs to (never taken from a request parameter). */
    @Column(name = "benutzer", nullable = false, length = 255)
    private String benutzer;

    /** {@link ch.plaintext.watch.page.WatchPage#id()} of the page last shown. */
    @Column(name = "aktuelle_seite", length = 64)
    private String aktuelleSeite;

    /** Whether the element gallery (test page) is available for this user. */
    @Column(name = "testseite_aktiv", nullable = false)
    private boolean testseiteAktiv = false;
}

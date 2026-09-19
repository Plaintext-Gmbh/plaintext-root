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

import java.time.LocalDateTime;

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

    /**
     * Whether the element gallery (test page) is available for this user.
     *
     * <p><b>No longer read (cards 1257/1260).</b> Superseded by {@link #abgeschalteteSeiten},
     * which switches every page and not just this one. The field stays so that the old value
     * survives a rollback; nothing writes it any more.</p>
     *
     * @deprecated use {@link #abgeschalteteSeiten}
     */
    @Deprecated(since = "1.694")
    @Column(name = "testseite_aktiv", nullable = false)
    private boolean testseiteAktiv = false;

    /**
     * Which watch pages this user has switched <b>off</b>, as {@link ch.plaintext.watch.page.WatchPage#id()} values
     * separated by commas. Empty or {@code null} means: everything the access rules allow.
     *
     * <p><b>Why the off-list and not the on-list.</b> A page a module adds tomorrow is then
     * visible by itself — today's behaviour. With a positive list every user would have to tick
     * every future page, and a release would make pages disappear without a word.</p>
     *
     * <p>Read and written exclusively through
     * {@code WatchStateService.seiteAktiv(String)} / {@code setzeSeite(String, boolean)}; the
     * string form never leaves the service.</p>
     */
    @Column(name = "abgeschaltete_seiten", length = 1024)
    private String abgeschalteteSeiten;

    /**
     * Whether the personal phone link of this user is switched on (card 1257).
     *
     * <p>A second lock next to the revocation in {@code api_token}: switching the link off
     * revokes the token <em>and</em> clears this flag. Whoever gets past one still fails at the
     * other.</p>
     */
    @Column(name = "handy_link_aktiv", nullable = false)
    private boolean handyLinkAktiv = false;

    /** When the currently valid phone link was issued — the only thing the settings page shows. */
    @Column(name = "handy_link_erstellt")
    private LocalDateTime handyLinkErstellt;

    /**
     * {@code jti} of the issued token, for tracing a link back to its row in {@code api_token}.
     *
     * <p><b>The token itself is deliberately not stored anywhere here.</b> It is a bearer
     * credential; like every other token in this house it exists only as a SHA-256 hash in
     * {@code api_token}. On top of that this table is exported by
     * {@code WatchModuleDescriptor} — a cleartext column would put a live credential into every
     * module export file. The link is therefore shown once, right after it is created.</p>
     */
    @Column(name = "handy_link_jti", length = 64)
    private String handyLinkJti;
}

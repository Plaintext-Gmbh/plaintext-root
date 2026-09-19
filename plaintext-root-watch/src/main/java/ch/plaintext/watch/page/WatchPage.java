/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.page;

/**
 * One page of the watch/small-screen view stack.
 *
 * <p>Every module that wants to appear on the watch contributes one Spring bean per page. The
 * registry collects them, orders them and moves between them — no module needs to know about
 * the others.</p>
 *
 * <h2>Why an interface and not a menu annotation</h2>
 *
 * <p>The regular menu ({@code @MenuAnnotation}) is built for a sidebar with room for labels and
 * icons. A watch screen shows <b>one</b> page at a time and is navigated by swiping or tapping
 * next/previous, so what matters here is the <em>order</em> and a stable {@link #id()} to
 * remember the user's position — not a tree.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public interface WatchPage {

    /**
     * Stable identifier, used to remember where the user was. Must not change once released —
     * it is persisted per user.
     */
    String id();

    /** Short title, shown in the header. Keep it to roughly 12 characters. */
    String title();

    /** View to render, relative to the resource root, e.g. {@code /watch/zeit.xhtml}. */
    String view();

    /** Lower numbers come first. The home page uses {@code 0}. */
    default int order() {
        return 100;
    }

    /**
     * Whether the <b>access rules</b> let the current user see this page — the module's roles,
     * usually asked of {@code PageAccessGuardService}.
     *
     * <p><b>This is not the user's own selection.</b> Since card 1257 every user can additionally
     * switch single pages off; that is stored per user and evaluated in
     * {@link WatchPageRegistry#sichtbar(WatchPage)}, not here. Both have to say yes: a page
     * switched off is additionally invisible, a page locked by role stays locked. A module
     * therefore keeps answering exactly one question here, the one it alone can answer.</p>
     *
     * <p>A page that is not visible is skipped when moving forward or back, and is not
     * reachable directly either.</p>
     */
    default boolean available() {
        return true;
    }

    /**
     * Whether this page takes part in the next/previous rotation and counts in the "x/n" in the
     * header.
     *
     * <p>{@code false} makes a page reachable only by its address or by a link of its own — it
     * is not stepped to and does not inflate the count. Card 1260: the element gallery became
     * the overview with the page switches. It is the only way to the switches, so it must not
     * be switchable off; but a reference sheet has no business in the everyday rotation
     * either.</p>
     *
     * <p>Deliberately separate from {@link #available()}: "not in the way" and "not allowed" are
     * different statements, and folding them together would make the overview unreachable as
     * soon as somebody switched it off.</p>
     */
    default boolean imUmlauf() {
        return true;
    }
}

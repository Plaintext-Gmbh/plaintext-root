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

    /** View to render, relative to the resource root, e.g. {@code /nosec/watch/zeit.xhtml}. */
    String view();

    /** Lower numbers come first. The home page uses {@code 0}. */
    default int order() {
        return 100;
    }

    /**
     * Whether this page is available for the current user right now.
     *
     * <p>Used by pages that are switched on and off per user (the element gallery, for
     * instance). A page that returns {@code false} is skipped when moving forward or back, and
     * is not reachable directly.</p>
     */
    default boolean available() {
        return true;
    }
}

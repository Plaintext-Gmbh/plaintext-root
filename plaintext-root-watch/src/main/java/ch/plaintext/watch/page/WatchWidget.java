/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.page;

/**
 * One tile on the watch home screen.
 *
 * <p>Modules contribute widgets instead of building their own home page, so the overview stays
 * one screen no matter how many modules are installed.</p>
 *
 * <p>Keep {@link #value()} short — it is rendered at 2.1rem and must fit next to one or two
 * others. A number, a time, at most five characters. The explanation goes in
 * {@link #label()}.</p>
 */
public interface WatchWidget {

    String id();

    /** Uppercase caption under the value; roughly 10 characters. */
    String label();

    /** The value itself — a number or short text, computed for the signed-in user. */
    String value();

    default int order() {
        return 100;
    }

    default boolean available() {
        return true;
    }
}

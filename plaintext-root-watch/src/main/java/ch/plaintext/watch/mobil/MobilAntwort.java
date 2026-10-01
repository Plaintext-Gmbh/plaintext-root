/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.mobil;

/**
 * The outcome of an action, as the user reads it (Karte 1355).
 *
 * <p>The JSF watch pages collected a {@code FacesMessage} and the frame had no place to show
 * it — an action that failed looked exactly like one that worked. Here the answer is part of
 * the contract, and the framework shows it in a status line.</p>
 *
 * @param ok      whether the action did what the button says
 * @param meldung short sentence for the status line, may be {@code null}
 */
public record MobilAntwort(boolean ok, String meldung) {

    public static MobilAntwort ok(String meldung) {
        return new MobilAntwort(true, meldung);
    }

    public static MobilAntwort fehler(String meldung) {
        return new MobilAntwort(false, meldung);
    }
}

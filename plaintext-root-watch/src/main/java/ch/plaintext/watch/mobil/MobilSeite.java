/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.mobil;

import java.util.ArrayList;
import java.util.List;

/**
 * What a page shows — described, not drawn (Karte 1355).
 *
 * <p>A module lists a few blocks: a figure, a row of buttons, a list of entries. The framework
 * turns them into HTML ({@link MobilHtml}) with the classes of {@code watch.css}. A module
 * therefore writes no markup at all, and a page cannot drift from the others: the stylesheet
 * contract ({@code WatchStilVertragTest}) and the CSP contract (no inline script, no
 * {@code style} attribute) are kept in exactly one class.</p>
 *
 * <h2>Why a closed vocabulary</h2>
 *
 * <p>Four block types cover all five watch pages that exist today (alkohol, kalorien, zeit,
 * challenge, kalender). A page that needs a fifth kind gets it here, once, for everybody —
 * not as a free HTML string, which would reopen exactly the door the escaping in
 * {@link MobilHtml} closes.</p>
 *
 * @param bausteine the blocks, top to bottom
 */
public record MobilSeite(List<Baustein> bausteine) {

    public MobilSeite {
        bausteine = List.copyOf(bausteine);
    }

    public static Builder neu() {
        return new Builder();
    }

    /** One block of a page. */
    public sealed interface Baustein permits Wert, Knoepfe, Liste, Hinweis {
    }

    /**
     * One figure with its caption, e.g. "14 g" under "Heute".
     *
     * @param label  caption, short
     * @param wert   the figure, at most five or six characters
     * @param hinweis optional explanation below, {@code null} for none
     */
    public record Wert(String label, String wert, String hinweis) implements Baustein {
    }

    /**
     * A column of full-width buttons, each one tap, each one action.
     *
     * @param label  caption above the buttons, {@code null} for none
     * @param knoepfe the buttons, top to bottom
     */
    public record Knoepfe(String label, List<Knopf> knoepfe) implements Baustein {
        public Knoepfe {
            knoepfe = List.copyOf(knoepfe);
        }
    }

    /**
     * One button.
     *
     * @param aktion       name of the action, handed to {@link MobilWatchPage#handle}; lower
     *                     case, digits and dashes
     * @param wert         the value sent along (a type, an id), may be {@code null}
     * @param beschriftung what the button says
     */
    public record Knopf(String aktion, String wert, String beschriftung) {
    }

    /**
     * A list of entries.
     *
     * @param label    caption above the list
     * @param leerText what is shown when there are no entries
     * @param eintraege the entries, top to bottom
     */
    public record Liste(String label, String leerText, List<Eintrag> eintraege) implements Baustein {
        public Liste {
            eintraege = List.copyOf(eintraege);
        }
    }

    /**
     * One entry of a list.
     *
     * @param schluessel   stable key of the entry (an id); sent as {@code wert} when it is deleted
     * @param text         left, what it is
     * @param rechts       right, a time or a figure; may be {@code null}
     * @param loeschAktion action that deletes the entry after an inline confirmation;
     *                     {@code null} means the entry cannot be deleted
     */
    public record Eintrag(String schluessel, String text, String rechts, String loeschAktion) {
    }

    /** A plain line of explanation. */
    public record Hinweis(String text) implements Baustein {
    }

    /** Collects the blocks in order. */
    public static final class Builder {
        private final List<Baustein> bausteine = new ArrayList<>();

        public Builder wert(String label, String wert, String hinweis) {
            bausteine.add(new Wert(label, wert, hinweis));
            return this;
        }

        public Builder knoepfe(String label, List<Knopf> knoepfe) {
            bausteine.add(new Knoepfe(label, knoepfe));
            return this;
        }

        public Builder liste(String label, String leerText, List<Eintrag> eintraege) {
            bausteine.add(new Liste(label, leerText, eintraege));
            return this;
        }

        public Builder hinweis(String text) {
            bausteine.add(new Hinweis(text));
            return this;
        }

        public MobilSeite bauen() {
            return new MobilSeite(bausteine);
        }
    }
}

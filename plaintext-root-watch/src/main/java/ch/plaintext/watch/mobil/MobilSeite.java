/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.mobil;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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
 * <p>A page that needs a new kind of block gets it here, once, for everybody — not as a free
 * HTML string, which would reopen exactly the door the escaping in {@link MobilHtml} closes.</p>
 *
 * <p>Card 1387 moved every watch page onto this framework and added what they needed, each
 * kind once: {@link Kacheln} for the home screen, {@link Aktion} (the large acting button of
 * zeit and challenge, with a running time that keeps counting in the browser), {@link Schalter}
 * for the page switches of the overview, the chosen state of a {@link Knopf}, and entries with
 * a leading column, a line below and editable fields ({@link Eintrag}). The four original blocks
 * and their constructors are unchanged, so a page written for card 1355 compiles as it is.</p>
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
    public sealed interface Baustein permits Wert, Knoepfe, Liste, Hinweis, Kacheln, Aktion, Schalter {
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
     * @param gewaehlt     whether this is the currently chosen one of a row (drawn highlighted,
     *                     {@code aria-pressed}); card 1387, for the label chips of zeit
     */
    public record Knopf(String aktion, String wert, String beschriftung, boolean gewaehlt) {

        /** A button that is not a choice. */
        public Knopf(String aktion, String wert, String beschriftung) {
            this(aktion, wert, beschriftung, false);
        }
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
     *                     or changed
     * @param text         what it is
     * @param rechts       right, a time or a figure; may be {@code null}
     * @param loeschAktion action that deletes the entry after an inline confirmation;
     *                     {@code null} means the entry cannot be deleted
     * @param vorne        a short column before the text (the time of an appointment); may be
     *                     {@code null}
     * @param unter        a line below (place, time of a meal); may be {@code null}
     * @param aenderung    fields of the entry that can be changed in place (from/to of a time
     *                     entry); {@code null} for none
     */
    public record Eintrag(String schluessel, String text, String rechts, String loeschAktion,
                          String vorne, String unter, Aenderung aenderung) {

        /** The entry of card 1355: text, figure, delete. */
        public Eintrag(String schluessel, String text, String rechts, String loeschAktion) {
            this(schluessel, text, rechts, loeschAktion, null, null, null);
        }
    }

    /**
     * Fields of an entry that are changed in place (card 1387). With JavaScript every change is
     * saved at once, as a {@code fetch} — the way the time fields of zeit always worked; without
     * it the row carries a small OK button.
     *
     * <p>The action receives the key of the entry as {@code wert} and the fields by name
     * ({@link MobilWatchPage#handle(String, String, java.util.Map)}).</p>
     *
     * @param aktion action name, like {@link Knopf#aktion()}
     * @param felder the fields, left to right
     */
    public record Aenderung(String aktion, List<Feld> felder) {
        public Aenderung {
            felder = List.copyOf(felder);
        }
    }

    /** What kind of input a field is. Native inputs only: they bring the phone's own picker. */
    public enum FeldArt {
        ZEIT("time"), DATUM("date"), TEXT("text");

        private final String typ;

        FeldArt(String typ) {
            this.typ = typ;
        }

        /** The {@code type} attribute of the input. */
        public String typ() {
            return typ;
        }
    }

    /**
     * One field.
     *
     * @param name         name the page reads the value under; lower case and digits, it is sent
     *                     as {@code f-<name>}
     * @param art          time, date or text
     * @param wert         current value ({@code HH:mm}, {@code yyyy-MM-dd} or text); {@code null}
     *                     or blank leaves the field empty
     * @param beschriftung accessible name ("von", "bis")
     */
    public record Feld(String name, FeldArt art, String wert, String beschriftung) {

        /** What a field may be called — it ends up in a request parameter name. */
        static final Pattern NAME_MUSTER = Pattern.compile("[a-z][a-z0-9]{0,19}");

        public Feld {
            if (name == null || !NAME_MUSTER.matcher(name).matches()) {
                throw new IllegalArgumentException("Feldname ungültig: " + name);
            }
            if (art == null) {
                throw new IllegalArgumentException("Feldart fehlt: " + name);
            }
        }
    }

    /** A plain line of explanation. */
    public record Hinweis(String text) implements Baustein {
    }

    /**
     * A row of tiles — figure above, caption below (card 1387, the home screen). Two per row,
     * three from three tiles on.
     *
     * @param kacheln  the tiles
     * @param leerText what is shown when there are none
     */
    public record Kacheln(List<Kachel> kacheln, String leerText) implements Baustein {
        public Kacheln {
            kacheln = List.copyOf(kacheln);
        }
    }

    /** One tile: a short figure and its caption. */
    public record Kachel(String label, String wert) {
    }

    /** Colour of an {@link Aktion}: what the tap will do, in colour AND in the word. */
    public enum Farbe {
        NEUTRAL, GO, STOP
    }

    /**
     * The large acting button of a page (card 1312, here since card 1387): what is being worked
     * on, the figure for it and the word for what a tap does — all inside the button, one tap
     * area. Below it optional small buttons for the side action (challenge: −1).
     *
     * @param knopf         the action; its {@link Knopf#beschriftung()} is the large word
     * @param oben          first line inside the button (category, title); may be {@code null}
     * @param gross         the figure inside the button (duration, count); may be {@code null}
     * @param farbe         colour
     * @param laeuftSekunden when not {@code null}, {@code gross} is a running time that started
     *                      this many seconds ago — the browser keeps counting it (as {@code H:mm})
     *                      without asking the server
     * @param neben         small buttons below, may be empty
     */
    public record Aktion(Knopf knopf, String oben, String gross, Farbe farbe, Long laeuftSekunden,
                         List<Knopf> neben) implements Baustein {
        public Aktion {
            if (knopf == null) {
                throw new IllegalArgumentException("Aktion ohne Knopf");
            }
            farbe = farbe == null ? Farbe.NEUTRAL : farbe;
            neben = neben == null ? List.of() : List.copyOf(neben);
        }
    }

    /**
     * A list of switches, one row per thing that is on or off (card 1387, the page switches of
     * the overview). One tap flips one row and saves at once — on a watch, a form with checkboxes
     * and a save button is a form nobody fills in.
     *
     * @param label   caption above
     * @param zeilen  the rows
     * @param hinweis a note below, may be {@code null}
     */
    public record Schalter(String label, List<SchalterZeile> zeilen, String hinweis) implements Baustein {
        public Schalter {
            zeilen = List.copyOf(zeilen);
        }
    }

    /**
     * One switch.
     *
     * @param titel  what it switches
     * @param aktion action that flips it, the {@code wert} is sent along
     * @param wert   which one (an id)
     * @param an     the current state
     */
    public record SchalterZeile(String titel, String aktion, String wert, boolean an) {
    }

    /** {@code H:mm} — the format of a running time on a display this size (seconds are noise). */
    public static String dauer(long sekunden) {
        long s = Math.max(0, sekunden);
        return String.format("%d:%02d", s / 3600, (s % 3600) / 60);
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

        public Builder kacheln(List<Kachel> kacheln, String leerText) {
            bausteine.add(new Kacheln(kacheln, leerText));
            return this;
        }

        public Builder aktion(Aktion aktion) {
            bausteine.add(aktion);
            return this;
        }

        public Builder schalter(String label, List<SchalterZeile> zeilen, String hinweis) {
            bausteine.add(new Schalter(label, zeilen, hinweis));
            return this;
        }

        /** Any block, for a page that builds one conditionally. */
        public Builder baustein(Baustein baustein) {
            bausteine.add(baustein);
            return this;
        }

        public MobilSeite bauen() {
            return new MobilSeite(bausteine);
        }
    }
}

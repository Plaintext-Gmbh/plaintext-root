/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.auswertung;

import ch.plaintext.arch.StabileApi;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;

/**
 * Turns the period a user picked in a drop-down ("Aktuelle Woche", "MONAT_03", "Q3",
 * "VOR_4_WOCHEN", …) into a from/to pair of dates.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>The same calendar arithmetic stood three times in this repository: in
 * {@code zeiterfassung.auswertung.Zeitraum} (complete, on {@code Calendar}) and twice more,
 * line for line, inside {@code PostkontoAuswertungBackingBean} and
 * {@code PostkontoTransaktionsflussBackingBean} — about 150 lines each (card 1240). The
 * alkohol evaluation was about to become the fourth (card 1250). Three copies of a rule are
 * three places to fix when a boundary is wrong, and nobody finds all three.
 *
 * <h2>Why {@code LocalDate} and not {@code Calendar}</h2>
 *
 * <p>The existing copy in zeiterfassung uses {@code Calendar}, and the bugs it invites are
 * exactly the ones this arithmetic is prone to: {@code MONTH} counts from zero,
 * {@code DAY_OF_WEEK} starts on Sunday in a default locale, and every instance carries a
 * mutable state that the next call changes. {@code LocalDate} has none of that, and
 * {@code TemporalAdjusters} says what is meant instead of computing it.
 *
 * <p>The <b>upper bound is exclusive</b> ({@code bis} is the first instant <em>after</em> the
 * period). The alternative — 23:59:59.999 — silently drops anything recorded in the last
 * millisecond of a day, and with {@code LocalDateTime} that millisecond is real.
 *
 * <h2>What deliberately stays outside</h2>
 *
 * <p>The {@code SelectItem} lists for the drop-down remain in the backing beans: they are
 * presentation, they differ per module (postkonto offers quarters, zeiterfassung does not),
 * and they would drag JSF into a class that is otherwise testable without a container.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@StabileApi("Auswertungszeitraum fuer Fachmodule (Karte 1504, aus app-interfaces)")
public record Auswertungszeitraum(LocalDate von, LocalDate bis) {

    public static final String HEUTE = "HEUTE";
    public static final String AKTUELLE_WOCHE = "AKTUELLE_WOCHE";
    public static final String LETZTE_WOCHE = "LETZTE_WOCHE";
    public static final String AKTUELLER_MONAT = "AKTUELLER_MONAT";
    public static final String LETZTER_MONAT = "LETZTER_MONAT";
    public static final String GANZES_JAHR = "GANZES_JAHR";
    public static final String HALBJAHR_1 = "HALBJAHR_1";
    public static final String HALBJAHR_2 = "HALBJAHR_2";

    /** {@code VOR_<n>_WOCHEN} — n whole weeks back, Monday to Sunday. */
    public static final String WOCHEN_PREFIX = "VOR_";
    public static final String WOCHEN_SUFFIX = "_WOCHEN";
    /** {@code MONAT_<1..12>}. */
    public static final String MONAT_PREFIX = "MONAT_";
    /** {@code Q1} … {@code Q4}. */
    public static final String QUARTAL_PREFIX = "Q";

    /** Sentinel for "all years" — the drop-downs use -1 for it. */
    public static final int ALLE_JAHRE = -1;

    public Auswertungszeitraum {
        if (von == null || bis == null) {
            throw new IllegalArgumentException("von und bis duerfen nicht null sein");
        }
        if (bis.isBefore(von)) {
            throw new IllegalArgumentException("bis (" + bis + ") liegt vor von (" + von + ")");
        }
    }

    /**
     * Resolves a period key for a year.
     *
     * @param schluessel one of the constants above, or {@code null}
     * @param jahr       the year; {@link #ALLE_JAHRE} widens the range to everything
     * @param heute      today — passed in rather than read from the clock, so the arithmetic
     *                   is testable without freezing time
     * @return the period, or empty if the key is unknown — the caller decides what an unknown
     *         selection means; guessing a default here would hide a typo in a drop-down
     */
    public static Optional<Auswertungszeitraum> fuer(String schluessel, int jahr, LocalDate heute) {
        if (schluessel == null || schluessel.isBlank() || heute == null) {
            return Optional.empty();
        }
        if (jahr == ALLE_JAHRE) {
            return Optional.of(new Auswertungszeitraum(LocalDate.of(1970, 1, 1), LocalDate.of(2100, 1, 1)));
        }
        return switch (schluessel) {
            case HEUTE -> Optional.of(tag(heute));
            case AKTUELLE_WOCHE -> Optional.of(woche(heute));
            case LETZTE_WOCHE -> Optional.of(woche(heute.minusWeeks(1)));
            case AKTUELLER_MONAT -> Optional.of(monat(heute));
            case LETZTER_MONAT -> Optional.of(monat(heute.minusMonths(1)));
            case GANZES_JAHR -> Optional.of(spanne(LocalDate.of(jahr, 1, 1), LocalDate.of(jahr + 1, 1, 1)));
            case HALBJAHR_1 -> Optional.of(spanne(LocalDate.of(jahr, 1, 1), LocalDate.of(jahr, 7, 1)));
            case HALBJAHR_2 -> Optional.of(spanne(LocalDate.of(jahr, 7, 1), LocalDate.of(jahr + 1, 1, 1)));
            default -> ausMuster(schluessel, jahr, heute);
        };
    }

    private static Optional<Auswertungszeitraum> ausMuster(String schluessel, int jahr, LocalDate heute) {
        if (schluessel.startsWith(WOCHEN_PREFIX) && schluessel.endsWith(WOCHEN_SUFFIX)) {
            return zahl(schluessel.substring(WOCHEN_PREFIX.length(),
                    schluessel.length() - WOCHEN_SUFFIX.length()))
                    .filter(n -> n >= 0)
                    .map(n -> woche(heute.minusWeeks(n)));
        }
        if (schluessel.startsWith(MONAT_PREFIX)) {
            return zahl(schluessel.substring(MONAT_PREFIX.length()))
                    .filter(m -> m >= 1 && m <= 12)
                    .map(m -> monat(LocalDate.of(jahr, Month.of(m), 1)));
        }
        if (schluessel.startsWith(QUARTAL_PREFIX)) {
            return zahl(schluessel.substring(QUARTAL_PREFIX.length()))
                    .filter(q -> q >= 1 && q <= 4)
                    .map(q -> spanne(LocalDate.of(jahr, q * 3 - 2, 1),
                            LocalDate.of(jahr, q * 3 - 2, 1).plusMonths(3)));
        }
        return Optional.empty();
    }

    private static Optional<Integer> zahl(String s) {
        try {
            return Optional.of(Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Auswertungszeitraum tag(LocalDate t) {
        return new Auswertungszeitraum(t, t.plusDays(1));
    }

    /** Monday to Monday — ISO, not locale-dependent: in a US locale the week would start on Sunday. */
    private static Auswertungszeitraum woche(LocalDate irgendwannInDerWoche) {
        LocalDate montag = irgendwannInDerWoche.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        return new Auswertungszeitraum(montag, montag.plusWeeks(1));
    }

    private static Auswertungszeitraum monat(LocalDate irgendwannImMonat) {
        LocalDate erster = irgendwannImMonat.withDayOfMonth(1);
        return new Auswertungszeitraum(erster, erster.plusMonths(1));
    }

    private static Auswertungszeitraum spanne(LocalDate von, LocalDate bisAusschliesslich) {
        return new Auswertungszeitraum(von, bisAusschliesslich);
    }

    /** {@code true} if the instant falls inside — lower bound inclusive, upper bound exclusive. */
    public boolean enthaelt(LocalDateTime zeitpunkt) {
        if (zeitpunkt == null) {
            return false;
        }
        LocalDate tag = zeitpunkt.toLocalDate();
        return !tag.isBefore(von) && tag.isBefore(bis);
    }

    /** Start of the period as an instant — for queries that compare {@code LocalDateTime}. */
    public LocalDateTime vonZeitpunkt() {
        return von.atStartOfDay();
    }

    /** First instant AFTER the period — use with {@code <}, never with {@code <=}. */
    public LocalDateTime bisZeitpunkt() {
        return bis.atStartOfDay();
    }

    /** Number of days covered; at least one. */
    public long tage() {
        return java.time.temporal.ChronoUnit.DAYS.between(von, bis);
    }
}

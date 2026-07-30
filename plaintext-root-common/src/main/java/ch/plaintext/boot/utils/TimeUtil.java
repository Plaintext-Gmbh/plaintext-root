/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.utils;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.joda.time.DateTime;
import org.springframework.stereotype.Controller;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

// TODO-REFACTOR-005: Utility-Klasse ist als @Controller annotiert
// Begründung: TimeUtil ist eine Utility-Klasse mit nur static methods, sollte nicht @Controller sein
// Vorschlag: @Controller entfernen, private Constructor hinzufügen, oder zu Component wechseln

// TODO-REFACTOR-109: Massive Code-Duplikation durch wiederholte SimpleDateFormat Instanziierung
// Begründung: In jeder Methode wird neues SimpleDateFormat erstellt (20+ mal im Code)
// Vorschlag: Private static final DateTimeFormatter Constants definieren
//            z.B. private static final DateTimeFormatter DATE_SHORT = DateTimeFormatter.ofPattern("dd.MM.yyyy")

// TODO-REFACTOR-401: Performance - SimpleDateFormat ist nicht thread-safe und langsam
// Begründung: SimpleDateFormat bei jedem Aufruf neu erstellen ist ineffizient
//             SimpleDateFormat ist nicht thread-safe (Problem bei concurrent calls)
// Vorschlag: Migration zu java.time.DateTimeFormatter (thread-safe, performanter)
//            Als Alternative: ThreadLocal<SimpleDateFormat> verwenden

// TODO-REFACTOR-110: Mix von java.util.Date und Joda-Time DateTime
// Begründung: Klasse verwendet sowohl java.util.Date als auch org.joda.time.DateTime
//             Joda-Time ist deprecated seit Java 8 (java.time Package verfügbar)
// Vorschlag: Vollständige Migration zu java.time.* (LocalDate, LocalDateTime, ZonedDateTime)

// TODO-REFACTOR-204: Inkonsistente und unklare Naming Convention
// Begründung: getTodayAsString() vs getDateAsStringShort() sind nicht selbsterklärend
//             "Tec" in getDateAsTecString() ist unklar (Technical?)
// Vorschlag: Umbenennen zu: formatToday(), formatDateShort(), formatDateTechnical()

// TODO-REFACTOR-304: Schlechtes Error Handling - Exception wird verschluckt
// Begründung: fromEmadDate() catcht ParseException, loggt nur, gibt null zurück
//             Caller weiß nicht, ob null = fehler oder null = kein Datum
// Vorschlag: Checked Exception werfen oder Optional<Date> zurückgeben

/**
 * Time / Date Utils
 *
 * @author Plaintext GmbH
 * @since 0.0.1
 */
@Slf4j
@Controller
public class TimeUtil {

    public static String getTodayAsString() {
        return getDateAsStringShort(new Date());
    }

    public static String getDateAsString(Date date) {
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");
        return fmt.format(date);
    }

    public static Date fromEmadDate(String in) {
        SimpleDateFormat fmt = new SimpleDateFormat("yy-MM-dd");
        try {
            return fmt.parse(in);
        } catch (ParseException e) {
            log.error(e.getMessage());
        }
        return null;
    }

    public static String getDateAsTecString(Date date) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyMMdd-HHmmssSSS");
        return fmt.format(date);
    }

    public static String getDateAsStringShort(Date date) {
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy");
        return fmt.format(date);
    }

    public static String getDateAsStringUltraShort(Date date) {
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yy");
        return fmt.format(date);
    }

    public static String getDateTimeAsString(Date date) {
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy HH:mm");
        return fmt.format(date);
    }

    public static String getDateTimeAsStringWochentag(Date date) {
        SimpleDateFormat fmt = new SimpleDateFormat("EEE, dd.MM.yyyy HH:mm");
        return fmt.format(date);
    }

    public static String getTimeInbetween(Date von, Date bis) {
        if (von == null || bis == null) {
            return "---:--";
        }

        Long millis = bis.getTime() - von.getTime();

        long sec = millis / 1000;

        long s = sec % 60;
        //long min = sec - s;

        long min = sec / 60;

        String se = "00" + s;
        String mi = "00" + min;

        return StringUtils.right(mi, 3) + ":" + StringUtils.right(se, 2);
    }

    public static Date getAsDate(String in) {
        // 11.02.2017 08:00:00
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");
        try {
            return fmt.parse(in);
        } catch (ParseException e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }

    public static Date getDateForJahr(String in) {
        // 1.1.xxxx
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy");
        try {
            return fmt.parse("1.1." + in);
        } catch (ParseException e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }


    public static Date getAsDateShort(String in) {
        // 11.02.2017
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy");
        try {
            return fmt.parse(in);
        } catch (ParseException e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }

    public static Date getAsSortableDate(String in) {
        // 20170211
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd");
        try {
            return fmt.parse(in);
        } catch (ParseException e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }

    public static Integer getJahr(Date in) {
        if (in == null) {
            return 0;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy");
        return Integer.parseInt(fmt.format(in));
    }

    public static Integer getJahrNow() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy");
        return Integer.parseInt(fmt.format(new Date()));
    }

    public static String getJahrNowString() {
        return "" + getJahrNow();
    }

    public static Integer getJahrNext() {
        int jahr = Calendar.getInstance().get(Calendar.YEAR);
        jahr++;
        return jahr;
    }

    public static Integer getJahrLast() {
        int jahr = Calendar.getInstance().get(Calendar.YEAR);
        jahr--;
        return jahr;
    }

    public static Integer[] getDiesesUndNaechstesJahr() {
        Integer[] jahr = new Integer[2];
        jahr[0] = getJahr(new Date());
        jahr[1] = jahr[0] + 1;
        return jahr;
    }

    public static Integer[] getLetztesDiesesUndNaechstesJahr() {
        Integer[] jahr = new Integer[3];
        jahr[0] = getJahr(new Date());
        jahr[1] = jahr[0] - 1;
        jahr[2] = jahr[0] + 1;
        return jahr;
    }

    public static List<String> getLetztesDiesesUndNaechstesJahrString() {
        List<String> ret = new ArrayList<>();
        Integer dieses = TimeUtil.getJahr(new Date());
        ret.add("" + (dieses - 1));
        ret.add("" + dieses);
        ret.add("" + (dieses + 1));
        return ret;
    }

    public static List<Integer> getLetztesDiesesUndNaechstesJahrInteger() {
        List<Integer> ret = new ArrayList<>();
        Integer dieses = TimeUtil.getJahr(new Date());
        ret.add((dieses - 1));
        ret.add(dieses);
        ret.add((dieses + 1));
        return ret;
    }

    public static List<String> getJahre() {
        List<String> ret = new ArrayList<>();
        Integer dieses = TimeUtil.getJahr(new Date());
        ret.add("" + (dieses - 2));
        ret.add("" + (dieses - 1));
        ret.add("" + dieses);
        ret.add("" + (dieses + 1));
        ret.add("" + (dieses + 2));
        return ret;
    }

    public static List<String> getJahreNext() {
        List<String> ret = new ArrayList<>();
        Integer dieses = TimeUtil.getJahr(new Date());
        ret.add("" + dieses);
        ret.add("" + (dieses + 1));
        return ret;
    }

    // zum testen auf nicht prod stages
    public static List<String> getJahre2() {
        List<String> ret = new ArrayList<>();
        Integer dieses = TimeUtil.getJahr(new Date());
        ret.add("" + (dieses - 2));
        ret.add("" + (dieses - 1));
        ret.add("" + dieses);
        ret.add("" + (dieses + 1));
        ret.add("" + (dieses + 2));
        ret.add("" + (dieses + 3));
        ret.add("" + (dieses + 4));
        ret.add("" + (dieses + 5));
        ret.add("" + (dieses + 6));
        ret.add("" + (dieses + 7));
        ret.add("" + (dieses + 8));
        ret.add("" + (dieses + 9));
        return ret;
    }

    public static Date datePlusYear(Date d, int jahr) {
        Calendar cal = new GregorianCalendar();
        cal.setTime(d);
        cal.set(Calendar.YEAR, cal.get(Calendar.YEAR) + jahr);
        return cal.getTime();
    }

    public static Date datePlusDay(Date d, int tag) {
        DateTime in = new DateTime(d);
        DateTime out = in.plusDays(tag);
        return out.toDate();
    }

    public static Date dateMinusDay(Date d, int tag) {
        DateTime in = new DateTime(d);
        DateTime out = in.minusDays(tag);
        return out.toDate();
    }

    public static boolean isToday(Date in){
        return DateUtils.isSameDay(new Date(),in);
    }

    public static DateTime toJoda(Date date) {
        return new DateTime(date);
    }

    public static Date toJava(DateTime date) {
        return date.toDate();
    }

}
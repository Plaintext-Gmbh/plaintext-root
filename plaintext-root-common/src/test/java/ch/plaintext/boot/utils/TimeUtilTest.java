/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.utils;

import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TimeUtilTest {

    // -------------------------------------------------------------------------
    // getTodayAsString
    // -------------------------------------------------------------------------

    @Test
    void getTodayAsString_returnsFormattedToday() {
        String today = TimeUtil.getTodayAsString();
        assertNotNull(today);
        // Format: dd.MM.yyyy
        assertTrue(today.matches("\\d{2}\\.\\d{2}\\.\\d{4}"),
                "Expected dd.MM.yyyy format but got: " + today);
    }

    // -------------------------------------------------------------------------
    // getDateAsString
    // -------------------------------------------------------------------------

    @Test
    void getDateAsString_formatsGivenDate() {
        Calendar cal = new GregorianCalendar(2023, Calendar.MARCH, 5, 14, 30, 45);
        String result = TimeUtil.getDateAsString(cal.getTime());
        assertEquals("05.03.2023 14:30:45", result);
    }

    // -------------------------------------------------------------------------
    // fromEmadDate
    // -------------------------------------------------------------------------

    @Test
    void fromEmadDate_validInput_returnsDate() {
        Date result = TimeUtil.fromEmadDate("23-06-15");
        assertNotNull(result);
        Calendar cal = Calendar.getInstance();
        cal.setTime(result);
        assertEquals(6, cal.get(Calendar.MONTH) + 1); // June
        assertEquals(15, cal.get(Calendar.DAY_OF_MONTH));
    }

    @Test
    void fromEmadDate_invalidInput_returnsNull() {
        Date result = TimeUtil.fromEmadDate("not-a-date");
        assertNull(result);
    }

    // -------------------------------------------------------------------------
    // getDateAsTecString
    // -------------------------------------------------------------------------

    @Test
    void getDateAsTecString_formatsGivenDate() {
        Calendar cal = new GregorianCalendar(2023, Calendar.MARCH, 5, 14, 30, 45);
        cal.set(Calendar.MILLISECOND, 123);
        String result = TimeUtil.getDateAsTecString(cal.getTime());
        assertEquals("230305-143045123", result);
    }

    // -------------------------------------------------------------------------
    // getDateAsStringShort
    // -------------------------------------------------------------------------

    @Test
    void getDateAsStringShort_formatsCorrectly() {
        Calendar cal = new GregorianCalendar(2023, Calendar.MARCH, 5);
        Date date = cal.getTime();
        String result = TimeUtil.getDateAsStringShort(date);
        assertEquals("05.03.2023", result);
    }

    // -------------------------------------------------------------------------
    // getDateAsStringUltraShort
    // -------------------------------------------------------------------------

    @Test
    void getDateAsStringUltraShort_formatsCorrectly() {
        Calendar cal = new GregorianCalendar(2023, Calendar.MARCH, 5);
        Date date = cal.getTime();
        String result = TimeUtil.getDateAsStringUltraShort(date);
        assertEquals("05.03.23", result);
    }

    // -------------------------------------------------------------------------
    // getDateTimeAsString
    // -------------------------------------------------------------------------

    @Test
    void getDateTimeAsString_formatsCorrectly() {
        Calendar cal = new GregorianCalendar(2023, Calendar.MARCH, 5, 14, 30);
        Date date = cal.getTime();
        String result = TimeUtil.getDateTimeAsString(date);
        assertEquals("05.03.2023 14:30", result);
    }

    // -------------------------------------------------------------------------
    // getDateTimeAsStringWochentag
    // -------------------------------------------------------------------------

    @Test
    void getDateTimeAsStringWochentag_includesDayOfWeek() {
        Calendar cal = new GregorianCalendar(2023, Calendar.MARCH, 5, 14, 30);
        Date date = cal.getTime();
        String result = TimeUtil.getDateTimeAsStringWochentag(date);
        assertNotNull(result);
        // Should contain the date portion
        assertTrue(result.contains("05.03.2023 14:30"),
                "Expected date portion in result but got: " + result);
    }

    // -------------------------------------------------------------------------
    // getTimeInbetween
    // -------------------------------------------------------------------------

    @Test
    void getTimeInbetween_nullVon_returnsDashes() {
        assertEquals("---:--", TimeUtil.getTimeInbetween(null, new Date()));
    }

    @Test
    void getTimeInbetween_nullBis_returnsDashes() {
        assertEquals("---:--", TimeUtil.getTimeInbetween(new Date(), null));
    }

    @Test
    void getTimeInbetween_bothNull_returnsDashes() {
        assertEquals("---:--", TimeUtil.getTimeInbetween(null, null));
    }

    @Test
    void getTimeInbetween_validDates_returnsFormattedDuration() {
        Calendar cal1 = new GregorianCalendar(2023, Calendar.MARCH, 5, 10, 0, 0);
        Calendar cal2 = new GregorianCalendar(2023, Calendar.MARCH, 5, 10, 5, 30);
        String result = TimeUtil.getTimeInbetween(cal1.getTime(), cal2.getTime());
        assertNotNull(result);
        // 5 minutes and 30 seconds
        // StringUtils.right("00" + 5, 3) = "005", StringUtils.right("00" + 30, 2) = "30"
        assertEquals("005:30", result);
    }

    @Test
    void getTimeInbetween_zeroDifference_returnsZero() {
        Date now = new Date();
        String result = TimeUtil.getTimeInbetween(now, now);
        assertEquals("000:00", result);
    }

    // -------------------------------------------------------------------------
    // getAsDate
    // -------------------------------------------------------------------------

    @Test
    void getAsDate_validInput_returnsDate() {
        Date result = TimeUtil.getAsDate("11.02.2017 08:00:00");
        assertNotNull(result);
        Calendar cal = Calendar.getInstance();
        cal.setTime(result);
        assertEquals(2017, cal.get(Calendar.YEAR));
        assertEquals(Calendar.FEBRUARY, cal.get(Calendar.MONTH));
        assertEquals(11, cal.get(Calendar.DAY_OF_MONTH));
        assertEquals(8, cal.get(Calendar.HOUR_OF_DAY));
    }

    @Test
    void getAsDate_invalidInput_returnsNull() {
        assertNull(TimeUtil.getAsDate("invalid"));
    }

    // -------------------------------------------------------------------------
    // getDateForJahr
    // -------------------------------------------------------------------------

    @Test
    void getDateForJahr_validYear_returnsJan1() {
        Date result = TimeUtil.getDateForJahr("2023");
        assertNotNull(result);
        Calendar cal = Calendar.getInstance();
        cal.setTime(result);
        assertEquals(2023, cal.get(Calendar.YEAR));
        assertEquals(Calendar.JANUARY, cal.get(Calendar.MONTH));
        assertEquals(1, cal.get(Calendar.DAY_OF_MONTH));
    }

    @Test
    void getDateForJahr_invalidYear_returnsNull() {
        assertNull(TimeUtil.getDateForJahr("abc"));
    }

    // -------------------------------------------------------------------------
    // getAsDateShort
    // -------------------------------------------------------------------------

    @Test
    void getAsDateShort_validInput_returnsDate() {
        Date result = TimeUtil.getAsDateShort("11.02.2017");
        assertNotNull(result);
        Calendar cal = Calendar.getInstance();
        cal.setTime(result);
        assertEquals(2017, cal.get(Calendar.YEAR));
        assertEquals(Calendar.FEBRUARY, cal.get(Calendar.MONTH));
        assertEquals(11, cal.get(Calendar.DAY_OF_MONTH));
    }

    @Test
    void getAsDateShort_invalidInput_returnsNull() {
        assertNull(TimeUtil.getAsDateShort("not-a-date"));
    }

    // -------------------------------------------------------------------------
    // getAsSortableDate
    // -------------------------------------------------------------------------

    @Test
    void getAsSortableDate_validInput_returnsDate() {
        Date result = TimeUtil.getAsSortableDate("20170211");
        assertNotNull(result);
        Calendar cal = Calendar.getInstance();
        cal.setTime(result);
        assertEquals(2017, cal.get(Calendar.YEAR));
        assertEquals(Calendar.FEBRUARY, cal.get(Calendar.MONTH));
        assertEquals(11, cal.get(Calendar.DAY_OF_MONTH));
    }

    @Test
    void getAsSortableDate_invalidInput_returnsNull() {
        assertNull(TimeUtil.getAsSortableDate("xyz"));
    }

    // -------------------------------------------------------------------------
    // getJahr
    // -------------------------------------------------------------------------

    @Test
    void getJahr_nullInput_returnsZero() {
        assertEquals(0, TimeUtil.getJahr(null));
    }

    @Test
    void getJahr_validDate_returnsYear() {
        Calendar cal = new GregorianCalendar(2023, Calendar.JUNE, 15);
        assertEquals(2023, TimeUtil.getJahr(cal.getTime()));
    }

    // -------------------------------------------------------------------------
    // getJahrNow / getJahrNowString
    // -------------------------------------------------------------------------

    @Test
    void getJahrNow_returnsCurrentYear() {
        int expectedYear = Calendar.getInstance().get(Calendar.YEAR);
        assertEquals(expectedYear, TimeUtil.getJahrNow());
    }

    @Test
    void getJahrNowString_returnsCurrentYearAsString() {
        String expected = String.valueOf(Calendar.getInstance().get(Calendar.YEAR));
        assertEquals(expected, TimeUtil.getJahrNowString());
    }

    // -------------------------------------------------------------------------
    // getJahrNext / getJahrLast
    // -------------------------------------------------------------------------

    @Test
    void getJahrNext_returnsNextYear() {
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        assertEquals(currentYear + 1, TimeUtil.getJahrNext());
    }

    @Test
    void getJahrLast_returnsLastYear() {
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        assertEquals(currentYear - 1, TimeUtil.getJahrLast());
    }

    // -------------------------------------------------------------------------
    // getDiesesUndNaechstesJahr
    // -------------------------------------------------------------------------

    @Test
    void getDiesesUndNaechstesJahr_returnsTwoYears() {
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        Integer[] result = TimeUtil.getDiesesUndNaechstesJahr();
        assertEquals(2, result.length);
        assertEquals(currentYear, result[0]);
        assertEquals(currentYear + 1, result[1]);
    }

    // -------------------------------------------------------------------------
    // getLetztesDiesesUndNaechstesJahr
    // -------------------------------------------------------------------------

    @Test
    void getLetztesDiesesUndNaechstesJahr_returnsThreeYears() {
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        Integer[] result = TimeUtil.getLetztesDiesesUndNaechstesJahr();
        assertEquals(3, result.length);
        assertEquals(currentYear, result[0]);
        assertEquals(currentYear - 1, result[1]);
        assertEquals(currentYear + 1, result[2]);
    }

    // -------------------------------------------------------------------------
    // getLetztesDiesesUndNaechstesJahrString
    // -------------------------------------------------------------------------

    @Test
    void getLetztesDiesesUndNaechstesJahrString_returnsThreeYearStrings() {
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        List<String> result = TimeUtil.getLetztesDiesesUndNaechstesJahrString();
        assertEquals(3, result.size());
        assertEquals(String.valueOf(currentYear - 1), result.get(0));
        assertEquals(String.valueOf(currentYear), result.get(1));
        assertEquals(String.valueOf(currentYear + 1), result.get(2));
    }

    // -------------------------------------------------------------------------
    // getLetztesDiesesUndNaechstesJahrInteger
    // -------------------------------------------------------------------------

    @Test
    void getLetztesDiesesUndNaechstesJahrInteger_returnsThreeYearIntegers() {
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        List<Integer> result = TimeUtil.getLetztesDiesesUndNaechstesJahrInteger();
        assertEquals(3, result.size());
        assertEquals(currentYear - 1, result.get(0));
        assertEquals(currentYear, result.get(1));
        assertEquals(currentYear + 1, result.get(2));
    }

    // -------------------------------------------------------------------------
    // getJahre
    // -------------------------------------------------------------------------

    @Test
    void getJahre_returnsFiveYears() {
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        List<String> result = TimeUtil.getJahre();
        assertEquals(5, result.size());
        assertEquals(String.valueOf(currentYear - 2), result.get(0));
        assertEquals(String.valueOf(currentYear + 2), result.get(4));
    }

    // -------------------------------------------------------------------------
    // getJahreNext
    // -------------------------------------------------------------------------

    @Test
    void getJahreNext_returnsTwoYears() {
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        List<String> result = TimeUtil.getJahreNext();
        assertEquals(2, result.size());
        assertEquals(String.valueOf(currentYear), result.get(0));
        assertEquals(String.valueOf(currentYear + 1), result.get(1));
    }

    // -------------------------------------------------------------------------
    // getJahre2
    // -------------------------------------------------------------------------

    @Test
    void getJahre2_returnsTwelveYears() {
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        List<String> result = TimeUtil.getJahre2();
        assertEquals(12, result.size());
        assertEquals(String.valueOf(currentYear - 2), result.get(0));
        assertEquals(String.valueOf(currentYear + 9), result.get(11));
    }

    // -------------------------------------------------------------------------
    // datePlusYear
    // -------------------------------------------------------------------------

    @Test
    void datePlusYear_addsYears() {
        Calendar cal = new GregorianCalendar(2023, Calendar.JUNE, 15);
        Date result = TimeUtil.datePlusYear(cal.getTime(), 2);
        Calendar resultCal = Calendar.getInstance();
        resultCal.setTime(result);
        assertEquals(2025, resultCal.get(Calendar.YEAR));
        assertEquals(Calendar.JUNE, resultCal.get(Calendar.MONTH));
        assertEquals(15, resultCal.get(Calendar.DAY_OF_MONTH));
    }

    @Test
    void datePlusYear_subtractsYearsWithNegative() {
        Calendar cal = new GregorianCalendar(2023, Calendar.JUNE, 15);
        Date result = TimeUtil.datePlusYear(cal.getTime(), -1);
        Calendar resultCal = Calendar.getInstance();
        resultCal.setTime(result);
        assertEquals(2022, resultCal.get(Calendar.YEAR));
    }

    // -------------------------------------------------------------------------
    // datePlusDay
    // -------------------------------------------------------------------------

    @Test
    void datePlusDay_addsDays() {
        Calendar cal = new GregorianCalendar(2023, Calendar.MARCH, 5);
        Date result = TimeUtil.datePlusDay(cal.getTime(), 3);
        Calendar resultCal = Calendar.getInstance();
        resultCal.setTime(result);
        assertEquals(8, resultCal.get(Calendar.DAY_OF_MONTH));
    }

    @Test
    void datePlusDay_crossesMonthBoundary() {
        Calendar cal = new GregorianCalendar(2023, Calendar.JANUARY, 30);
        Date result = TimeUtil.datePlusDay(cal.getTime(), 3);
        Calendar resultCal = Calendar.getInstance();
        resultCal.setTime(result);
        assertEquals(Calendar.FEBRUARY, resultCal.get(Calendar.MONTH));
        assertEquals(2, resultCal.get(Calendar.DAY_OF_MONTH));
    }

    // -------------------------------------------------------------------------
    // dateMinusDay
    // -------------------------------------------------------------------------

    @Test
    void dateMinusDay_subtractsDays() {
        Calendar cal = new GregorianCalendar(2023, Calendar.MARCH, 10);
        Date result = TimeUtil.dateMinusDay(cal.getTime(), 5);
        Calendar resultCal = Calendar.getInstance();
        resultCal.setTime(result);
        assertEquals(5, resultCal.get(Calendar.DAY_OF_MONTH));
    }

    @Test
    void dateMinusDay_crossesMonthBoundary() {
        Calendar cal = new GregorianCalendar(2023, Calendar.MARCH, 2);
        Date result = TimeUtil.dateMinusDay(cal.getTime(), 3);
        Calendar resultCal = Calendar.getInstance();
        resultCal.setTime(result);
        assertEquals(Calendar.FEBRUARY, resultCal.get(Calendar.MONTH));
        assertEquals(27, resultCal.get(Calendar.DAY_OF_MONTH));
    }

    // -------------------------------------------------------------------------
    // isToday
    // -------------------------------------------------------------------------

    @Test
    void isToday_withToday_returnsTrue() {
        assertTrue(TimeUtil.isToday(new Date()));
    }

    @Test
    void isToday_withYesterday_returnsFalse() {
        Date yesterday = TimeUtil.dateMinusDay(new Date(), 1);
        assertFalse(TimeUtil.isToday(yesterday));
    }

    // -------------------------------------------------------------------------
    // toJoda / toJava
    // -------------------------------------------------------------------------

    @Test
    void toJoda_convertsToJodaDateTime() {
        Date javaDate = new Date();
        DateTime jodaDate = TimeUtil.toJoda(javaDate);
        assertNotNull(jodaDate);
        assertEquals(javaDate.getTime(), jodaDate.getMillis());
    }

    @Test
    void toJava_convertsToJavaDate() {
        DateTime jodaDate = new DateTime(2023, 6, 15, 10, 30);
        Date javaDate = TimeUtil.toJava(jodaDate);
        assertNotNull(javaDate);
        assertEquals(jodaDate.getMillis(), javaDate.getTime());
    }

    @Test
    void toJoda_toJava_roundTrip() {
        Date original = new Date();
        Date roundTripped = TimeUtil.toJava(TimeUtil.toJoda(original));
        assertEquals(original.getTime(), roundTripped.getTime());
    }
}

package com.wasimaster.wmkeyboard.core.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A date typed into the Clock panel's box (Telegram group). */
class LocalDayParseTest {

    @Test fun dayFirstByDefault() {
        assertEquals(LocalDay(2004, 5, 17), LocalDay.parse("17/5/2004"))
        assertEquals(LocalDay(2004, 5, 7), LocalDay.parse("7.5.2004"))
        assertEquals(LocalDay(2004, 5, 17), LocalDay.parse("17-05-04"))
    }

    @Test fun yearFirstAndMonthFirst() {
        assertEquals(LocalDay(2004, 5, 17), LocalDay.parse("2004-05-17"))
        assertEquals(LocalDay(2004, 5, 7), LocalDay.parse("5/7/2004", monthFirst = true))
        // No 17th month: the other order is the only date it can be.
        assertEquals(LocalDay(2004, 5, 17), LocalDay.parse("5/17/2004"))
    }

    @Test fun twoDigitYearsAndNonDates() {
        assertEquals(LocalDay(1998, 1, 2), LocalDay.parse("2/1/98"))
        assertNull(LocalDay.parse("31/2/2004"))
        assertNull(LocalDay.parse("tomorrow"))
        assertNull(LocalDay.parse("17/5"))
    }
}

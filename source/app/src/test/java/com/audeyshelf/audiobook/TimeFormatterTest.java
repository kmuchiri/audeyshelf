package com.audeyshelf.audiobook;

import com.audeyshelf.audiobook.util.TimeFormatter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TimeFormatterTest {

    @Test
    public void testFormatMs_Zero() {
        assertEquals("00:00", TimeFormatter.formatMs(0));
    }

    @Test
    public void testFormatMs_Seconds() {
        assertEquals("00:45", TimeFormatter.formatMs(45000));
    }

    @Test
    public void testFormatMs_MinutesAndSeconds() {
        assertEquals("12:34", TimeFormatter.formatMs((12 * 60 + 34) * 1000L));
    }

    @Test
    public void testFormatMs_HoursMinutesSeconds() {
        assertEquals("1:02:03", TimeFormatter.formatMs((3600 + 2 * 60 + 3) * 1000L));
    }

    @Test
    public void testFormatMs_NegativeClamped() {
        assertEquals("00:00", TimeFormatter.formatMs(-5000));
    }

    @Test
    public void testFormatRemaining() {
        long current = 30000;
        long total = 90000;
        assertEquals("-01:00", TimeFormatter.formatRemaining(current, total));
    }

    @Test
    public void testFormatRemaining_Exceeded() {
        long current = 100000;
        long total = 90000;
        assertEquals("-00:00", TimeFormatter.formatRemaining(current, total));
    }

    @Test
    public void testFormatTimeLeft_NotStarted() {
        long current = 0;
        long total = (11 * 3600 + 18 * 60) * 1000L;
        assertEquals("11 hr 18 min left", TimeFormatter.formatTimeLeft(current, total));
    }

    @Test
    public void testFormatTimeLeft_PartiallyPlayed() {
        long current = (1 * 3600 + 24 * 60) * 1000L;
        long total = (11 * 3600 + 18 * 60) * 1000L;
        assertEquals("9 hr 54 min left", TimeFormatter.formatTimeLeft(current, total));
    }

    @Test
    public void testFormatTimeLeft_MinutesOnly() {
        long current = 10 * 60 * 1000L;
        long total = 45 * 60 * 1000L;
        assertEquals("35 min left", TimeFormatter.formatTimeLeft(current, total));
    }

    @Test
    public void testFormatTimeLeft_Completed() {
        long current = 50 * 60 * 1000L;
        long total = 50 * 60 * 1000L;
        assertEquals("Completed", TimeFormatter.formatTimeLeft(current, total));
    }
}

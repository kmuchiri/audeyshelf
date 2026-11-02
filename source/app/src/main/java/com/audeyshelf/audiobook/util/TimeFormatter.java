package com.audeyshelf.audiobook.util;

import java.util.Locale;

/**
 * Utility for formatting milliseconds to readable duration strings.
 */
public class TimeFormatter {

    public static String formatMs(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format(Locale.US, "%02d:%02d", minutes, seconds);
        }
    }

    public static String formatRemaining(long currentMs, long totalMs) {
        long remainingMs = Math.max(0, totalMs - currentMs);
        return "-" + formatMs(remainingMs);
    }

    public static String formatTimeLeft(long currentMs, long totalMs) {
        if (totalMs <= 0) return "";
        if (currentMs <= 0) {
            return formatDurationWords(totalMs) + " left";
        }
        long remainingMs = totalMs - currentMs;
        if (remainingMs <= 0) {
            return "Completed";
        }
        return formatDurationWords(remainingMs) + " left";
    }

    public static String formatDurationWords(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) {
            if (minutes > 0) {
                return String.format(Locale.US, "%d hr %d min", hours, minutes);
            } else {
                return String.format(Locale.US, "%d hr", hours);
            }
        } else if (minutes > 0) {
            return String.format(Locale.US, "%d min", minutes);
        } else {
            return String.format(Locale.US, "%d sec", Math.max(1, seconds));
        }
    }
}

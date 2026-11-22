package com.hafij.apphub;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loose dotted-number version comparison: "android-v0.9.6", "v1.4.1", "0.24.5-debug". */
final class Versions {
    private Versions() {}

    /**
     * Strips a tag down to the part where its version starts: the first digit that begins a word
     * ("1.2", "release-1.2") or follows a lone "v" ("v1.2", "files-v1.2", "mp3-v1.2"). Digits glued
     * to a name ("mp3", "x264") are skipped. Falls back to the first digit anywhere.
     */
    static String fromTag(String tag) {
        if (tag == null) return null;
        int first = -1;
        for (int i = 0; i < tag.length(); i++) {
            if (!Character.isDigit(tag.charAt(i))) continue;
            if (first < 0) first = i;
            if (i == 0) return tag;
            char p = tag.charAt(i - 1);
            if (!Character.isLetterOrDigit(p)) return tag.substring(i);
            if ((p == 'v' || p == 'V') && (i == 1 || !Character.isLetterOrDigit(tag.charAt(i - 2)))) {
                return tag.substring(i);
            }
        }
        return first >= 0 ? tag.substring(first) : tag;
    }

    /** Index just past the dotted-number run at the start of {@code v} (already stripped). */
    private static int numbersEnd(String v) {
        int i = 0;
        while (i < v.length() && Character.isDigit(v.charAt(i))) {
            while (i < v.length() && Character.isDigit(v.charAt(i))) i++;
            if (i + 1 < v.length() && v.charAt(i) == '.' && Character.isDigit(v.charAt(i + 1))) i++;
            else break;
        }
        return i;
    }

    static List<Integer> numbers(String v) {
        List<Integer> out = new ArrayList<>();
        if (v == null) return out;
        v = fromTag(v);
        int i = 0;
        while (i < v.length() && Character.isDigit(v.charAt(i))) {
            int start = i;
            while (i < v.length() && Character.isDigit(v.charAt(i))) i++;
            try {
                out.add(Integer.parseInt(v.substring(start, i)));
            } catch (NumberFormatException e) {
                out.add(Integer.MAX_VALUE);
            }
            if (i < v.length() && v.charAt(i) == '.') i++;
            else break;
        }
        return out;
    }

    private static final Pattern PRE = Pattern.compile(
            "^[-._+ ]?(alpha|beta|rc|pre|preview|dev|snapshot)(?![a-z])[-._]?(\\d*)");

    /**
     * Pre-release rank after the numbers: 0 = final (or an unknown suffix such as "-debug"),
     * negative = pre-release (dev/snapshot &lt; alpha &lt; beta &lt; pre/preview &lt; rc).
     * Returns {rank, number}.
     */
    static int[] preRelease(String v) {
        if (v == null) return new int[] {0, 0};
        v = fromTag(v);
        String rest = v.substring(numbersEnd(v)).toLowerCase(Locale.ROOT);
        Matcher m = PRE.matcher(rest);
        if (!m.find()) return new int[] {0, 0};
        int rank;
        switch (m.group(1)) {
            case "dev": case "snapshot": rank = -5; break;
            case "alpha": rank = -4; break;
            case "beta": rank = -3; break;
            case "pre": case "preview": rank = -2; break;
            default: rank = -1; // rc
        }
        int n = 0;
        if (!m.group(2).isEmpty()) {
            try {
                n = Integer.parseInt(m.group(2));
            } catch (NumberFormatException e) {
                n = Integer.MAX_VALUE;
            }
        }
        return new int[] {rank, n};
    }

    /** False for null, "?" and other version names without any digit. */
    static boolean known(String v) {
        return !numbers(v).isEmpty();
    }

    /** True when {@code remote} is a strictly higher version than {@code installed}. */
    static boolean newer(String remote, String installed) {
        List<Integer> a = numbers(remote), b = numbers(installed);
        // A tag without any number ("nightly") can't be ordered: never call it newer.
        if (a.isEmpty()) return false;
        if (b.isEmpty()) {
            // Unknown installed version ("?"): still offered for a manual install; the background
            // job does not auto-install these (see UpdateJob).
            return installed != null && !fromTag(remote).equals(fromTag(installed));
        }
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            int x = i < a.size() ? a.get(i) : 0, y = i < b.size() ? b.get(i) : 0;
            if (x != y) return x > y;
        }
        int[] pa = preRelease(remote), pb = preRelease(installed);
        if (pa[0] != pb[0]) return pa[0] > pb[0];
        return pa[1] > pb[1];
    }
}

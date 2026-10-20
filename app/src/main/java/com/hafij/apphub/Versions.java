package com.hafij.apphub;

import java.util.ArrayList;
import java.util.List;

/** Loose dotted-number version comparison: "android-v0.9.6", "v1.4.1", "0.24.5-debug". */
final class Versions {
    private Versions() {}

    /** Strips a tag down to the part starting at its first digit. */
    static String fromTag(String tag) {
        if (tag == null) return null;
        for (int i = 0; i < tag.length(); i++) {
            if (Character.isDigit(tag.charAt(i))) return tag.substring(i);
        }
        return tag;
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

    /** True when {@code remote} is a strictly higher version than {@code installed}. */
    static boolean newer(String remote, String installed) {
        List<Integer> a = numbers(remote), b = numbers(installed);
        if (a.isEmpty() || b.isEmpty()) {
            return remote != null && installed != null && !fromTag(remote).equals(fromTag(installed));
        }
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            int x = i < a.size() ? a.get(i) : 0, y = i < b.size() ? b.get(i) : 0;
            if (x != y) return x > y;
        }
        return false;
    }
}

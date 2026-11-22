package com.hafij.apphub;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Known signing certificates (SHA-256 of the DER certificate, as {@code apksigner verify
 * --print-certs} shows it) of the catalog apps. Compiled in on purpose: the remote catalog can
 * add apps but cannot change a key. A package with no entry here (apps the user added, new
 * catalog apps) is not checked, exactly as before.
 *
 * When an app's key changes, add the new digest here (keep the old one) and release App Hub first.
 */
final class Pins {
    private Pins() {}

    private static final Map<String, Set<String>> PINS = new HashMap<>();

    static {
        // Read 2026-11-21 from the published release APKs and the copies installed on both phones.
        pin("com.hafij.apphub",
                "9bf782c20ef1205a7cc6c835abca5f1fec0c1c8a7cab3ec102a8eb986e744aa9");
        pin("com.hafij.astraplayer",
                "aa41e235c09c748015fe350d314e5d1755e59fb6b0ab994d0cb9002a0162484d");
        pin("io.github.hafijbge.docscan",
                "0d5accebaeb219e5012b290a87e8ad0b672f2e22f5f85d419b370abcd7d96a50");
        pin("com.hafij.screenlingo",
                "f15743dc653e8b254edb4421396d746ff6b4b386db5125d0b97b88a3dc4617d1");
        pin("io.github.hafijbge.markd.debug",
                "67f42df1108c086af529dff7acba735e22b3e9f8a7a3b5e41c072b0434e5fc08");
        pin("io.github.hafijbge.extendo",
                "67f42df1108c086af529dff7acba735e22b3e9f8a7a3b5e41c072b0434e5fc08");
        pin("io.github.hafijbge.meshdrop",
                "d5fd987f52470a004489a3eb34044a301d1aa5fa1a4574a2645688c49a21b083");
        pin("com.hafij.harborfiles",
                "7e83521d8a6829a2028e4c26ca7c7214893208e64fdc8b566fe559824bb4b6fe");
        pin("io.github.hafijbge.worksuite",
                "165ae9cd100ad137cfd6423b7802a7f84e34b397f315169d64803f9977610b9a");
    }

    private static void pin(String pkg, String... digests) {
        Set<String> s = new HashSet<>();
        for (String d : digests) s.add(d.toLowerCase(Locale.ROOT));
        PINS.put(pkg, Collections.unmodifiableSet(s));
    }

    /** Pinned digests for {@code pkg}, or null when the package is not pinned. */
    static Set<String> forPackage(String pkg) {
        return pkg == null ? null : PINS.get(pkg);
    }

    /**
     * Null when {@code pkg} is not pinned or one of {@code certs} (DER bytes) matches a pin;
     * otherwise the error to show.
     */
    static String check(String pkg, Collection<byte[]> certs) {
        Set<String> want = forPackage(pkg);
        if (want == null) return null;
        if (certs == null || certs.isEmpty()) {
            return "Could not read the APK's signing key; not installed";
        }
        for (byte[] c : certs) {
            if (c != null && want.contains(sha256(c))) return null;
        }
        return "APK is not signed with the known key of " + pkg + "; not installed";
    }

    static String sha256(byte[] data) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** For tests. */
    static Set<String> pinnedPackages() {
        return new HashSet<>(Arrays.asList(PINS.keySet().toArray(new String[0])));
    }
}

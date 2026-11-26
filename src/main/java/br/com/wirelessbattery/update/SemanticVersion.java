package br.com.wirelessbattery.update;

import java.util.Objects;
import java.util.Optional;

/**
 * Representa e compara versões semânticas (ex.: {@code v1.2.0}, {@code 1.3.0-beta.1}, {@code 1.3.0}).
 */
public final class SemanticVersion implements Comparable<SemanticVersion> {

    private final int major;
    private final int minor;
    private final int patch;
    private final String preRelease;

    public SemanticVersion(int major, int minor, int patch, String preRelease) {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Componentes de versão não podem ser negativos");
        }
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.preRelease = preRelease == null ? "" : preRelease.trim();
    }

    public static SemanticVersion parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Versão vazia");
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            trimmed = trimmed.substring(1).trim();
        }
        int plusIdx = trimmed.indexOf('+');
        if (plusIdx >= 0) {
            trimmed = trimmed.substring(0, plusIdx);
        }

        String core;
        String pre = "";
        int dashIdx = trimmed.indexOf('-');
        if (dashIdx >= 0) {
            core = trimmed.substring(0, dashIdx);
            pre = trimmed.substring(dashIdx + 1).trim();
            if (pre.isEmpty()) {
                throw new IllegalArgumentException("Pré-lançamento vazio em: " + raw);
            }
        } else {
            core = trimmed;
        }

        String[] parts = core.split("\\.", -1);
        if (parts.length < 2 || parts.length > 3) {
            throw new IllegalArgumentException("Formato de versão inválido: " + raw);
        }
        try {
            int maj = Integer.parseInt(parts[0]);
            int min = Integer.parseInt(parts[1]);
            int pat = parts.length == 3 ? Integer.parseInt(parts[2]) : 0;
            return new SemanticVersion(maj, min, pat, pre);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Versão numérica inválida: " + raw, e);
        }
    }

    public static Optional<SemanticVersion> tryParse(String raw) {
        try {
            return Optional.of(parse(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public int patch() {
        return patch;
    }

    public String preRelease() {
        return preRelease;
    }

    public boolean isPreRelease() {
        return !preRelease.isEmpty();
    }

    @Override
    public int compareTo(SemanticVersion other) {
        Objects.requireNonNull(other, "other não pode ser nulo");
        int cmp = Integer.compare(this.major, other.major);
        if (cmp != 0) return cmp;
        cmp = Integer.compare(this.minor, other.minor);
        if (cmp != 0) return cmp;
        cmp = Integer.compare(this.patch, other.patch);
        if (cmp != 0) return cmp;

        boolean thisPre = this.isPreRelease();
        boolean otherPre = other.isPreRelease();
        if (!thisPre && !otherPre) return 0;
        if (!thisPre) return 1;
        if (!otherPre) return -1;

        return comparePreRelease(this.preRelease, other.preRelease);
    }

    private static int comparePreRelease(String a, String b) {
        String[] partsA = a.split("\\.", -1);
        String[] partsB = b.split("\\.", -1);
        int len = Math.min(partsA.length, partsB.length);
        for (int i = 0; i < len; i++) {
            String idA = partsA[i];
            String idB = partsB[i];
            boolean numA = isDigitsOnly(idA);
            boolean numB = isDigitsOnly(idB);
            if (numA && numB) {
                int c = Integer.compare(Integer.parseInt(idA), Integer.parseInt(idB));
                if (c != 0) return c;
            } else if (numA) {
                return -1;
            } else if (numB) {
                return 1;
            } else {
                int c = idA.compareTo(idB);
                if (c != 0) return c;
            }
        }
        return Integer.compare(partsA.length, partsB.length);
    }

    private static boolean isDigitsOnly(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        String base = major + "." + minor + "." + patch;
        return preRelease.isEmpty() ? base : base + "-" + preRelease;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SemanticVersion other)) return false;
        return major == other.major
                && minor == other.minor
                && patch == other.patch
                && preRelease.equals(other.preRelease);
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch, preRelease);
    }
}

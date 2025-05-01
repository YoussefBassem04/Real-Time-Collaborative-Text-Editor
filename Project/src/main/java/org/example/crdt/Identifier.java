package org.example.crdt;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Collections;
/**
 * Unique identifier for characters in the CRDT document.
 * Implements comparable for ordering in the sequence.
 */
public class Identifier implements Comparable<Identifier> {
    private final List<Integer> digits;
    private final int siteId;
    private final int clock;

    public Identifier(List<Integer> digits, int siteId, int clock) {
        if (digits == null) {
            throw new IllegalArgumentException("Digits list cannot be null");
        }
        this.digits = new ArrayList<>(digits);
        this.siteId = siteId;
        this.clock = clock;
    }

    /**
     * Creates a minimal identifier with empty digits
     */
    public static Identifier createMinimalIdentifier(int siteId) {
        return new Identifier(new ArrayList<>(), siteId, 0);
    }

    public List<Integer> getDigits() {
        return new ArrayList<>(digits);
    }

    public int getSiteId() {
        return siteId;
    }

    public int getClock() {
        return clock;
    }

    @Override
    public int compareTo(Identifier other) {
        if (other == null) {
            throw new IllegalArgumentException("Cannot compare with null");
        }

        // Compare digit by digit
        int minLength = Math.min(digits.size(), other.digits.size());
        for (int i = 0; i < minLength; i++) {
            int cmp = Integer.compare(digits.get(i), other.digits.get(i));
            if (cmp != 0) return cmp;
        }

        // If common prefix is equal, shorter comes first
        if (digits.size() != other.digits.size()) {
            return Integer.compare(digits.size(), other.digits.size());
        }

        // If digits are identical, compare siteId
        if (siteId != other.siteId) {
            return Integer.compare(siteId, other.siteId);
        }

        // If siteId is same, compare clock
        return Integer.compare(clock, other.clock);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Identifier that = (Identifier) o;
        return siteId == that.siteId &&
                clock == that.clock &&
                digits.equals(that.digits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(digits, siteId, clock);
    }

    @Override
    public String toString() {
        return "ID" + digits + "-S" + siteId + "-C" + clock;
    }

    /**
     * Helper method to get a copy with incremented clock
     */
    public Identifier withIncrementedClock() {
        return new Identifier(new ArrayList<>(digits), siteId, clock + 1);
    }
}
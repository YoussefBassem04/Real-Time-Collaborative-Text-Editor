package org.example.crdt;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class Position implements Serializable, Comparable<Position> {
    private static final long serialVersionUID = 1L;

    public static class Identifier implements Serializable, Comparable<Identifier> {
        private static final long serialVersionUID = 1L;
        private final double fraction; // Fractional index for ordering
        private final String clientId;

        public Identifier(double fraction, String clientId) {
            this.fraction = fraction;
            this.clientId = clientId;
        }

        public double getFraction() { return fraction; }
        public String getClientId() { return clientId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Identifier that = (Identifier) o;
            return Double.compare(that.fraction, fraction) == 0 &&
                    Objects.equals(clientId, that.clientId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(fraction, clientId);
        }

        @Override
        public String toString() {
            return fraction + ":" + clientId;
        }

        @Override
        public int compareTo(Identifier other) {
            int comp = Double.compare(this.fraction, other.fraction);
            if (comp != 0) return comp;
            return this.clientId.compareTo(other.clientId);
        }
    }

    private final List<Identifier> path;

    public Position() {
        this.path = new ArrayList<>();
    }

    public Position(List<Identifier> path) {
        this.path = new ArrayList<>(path);
    }

    public List<Identifier> getPath() {
        return new ArrayList<>(path);
    }

    public Position append(double fraction, String clientId) {
        Position newPos = new Position(this.path);
        newPos.path.add(new Identifier(fraction, clientId));
        return newPos;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Position position = (Position) o;
        return path.equals(position.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path);
    }

    @Override
    public int compareTo(Position other) {
        int minLength = Math.min(this.path.size(), other.path.size());
        for (int i = 0; i < minLength; i++) {
            int comp = this.path.get(i).compareTo(other.path.get(i));
            if (comp != 0) return comp;
        }
        return Integer.compare(this.path.size(), other.path.size());
    }

    public static Position between(Position before, Position after, String clientId) {
        double fractionBefore = before.path.isEmpty() ? 0.0 : before.path.get(before.path.size() - 1).getFraction();
        double fractionAfter = after.path.isEmpty() ? 1.0 : after.path.get(after.path.size() - 1).getFraction();
        double fraction = (fractionBefore + fractionAfter) / 2.0;
        return new Position().append(fraction, clientId);
    }
}
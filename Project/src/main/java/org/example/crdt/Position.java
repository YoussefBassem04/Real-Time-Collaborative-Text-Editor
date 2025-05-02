package org.example.crdt;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a position in a document using a path in the CRDT tree.
 * The position is defined by a list of identifiers (path) that uniquely
 * locate a character in the CRDT tree.
 */
public class Position implements Serializable, Comparable<Position> {

    private static final long serialVersionUID = 1L;

    /**
     * A single identifier in the path.
     * Each identifier has a position value and a client ID for uniqueness.
     */
    public static class Identifier implements Serializable, Comparable<Identifier> {
        private static final long serialVersionUID = 1L;

        private final int position;
        private final String clientId;

        /**
         * Creates a new identifier
         *
         * @param position The numeric position value
         * @param clientId The client ID for uniqueness
         */
        public Identifier(int position, String clientId) {
            this.position = position;
            this.clientId = clientId;
        }

        /**
         * Gets the position value
         *
         * @return The position value
         */
        public int getPosition() {
            return position;
        }

        /**
         * Gets the client ID
         *
         * @return The client ID
         */
        public String getClientId() {
            return clientId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Identifier that = (Identifier) o;
            return position == that.position &&
                    Objects.equals(clientId, that.clientId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(position, clientId);
        }

        @Override
        public String toString() {
            return position + ":" + clientId;
        }

        @Override
        public int compareTo(Identifier other) {
            if (this.position != other.position) {
                return Integer.compare(this.position, other.position);
            }
            return this.clientId.compareTo(other.clientId);
        }
    }

    private final List<Identifier> path;

    /**
     * Creates a new position with an empty path
     */
    public Position() {
        this.path = new ArrayList<>();
    }

    /**
     * Creates a new position with the specified path
     *
     * @param path The list of identifiers defining the path
     */
    public Position(List<Identifier> path) {
        this.path = new ArrayList<>(path);
    }

    /**
     * Gets the path of this position
     *
     * @return The path as a list of identifiers
     */
    public List<Identifier> getPath() {
        return new ArrayList<>(path);
    }

    /**
     * Creates a new position by appending an identifier to this position's path
     *
     * @param pos The position value for the new identifier
     * @param clientId The client ID for the new identifier
     * @return A new Position with the appended identifier
     */
    public Position append(int pos, String clientId) {
        Position newPos = new Position(this.path);
        newPos.path.add(new Identifier(pos, clientId));
        return newPos;
    }

    /**
     * Checks if this position is a direct ancestor of another position
     *
     * @param other The position to check against
     * @return True if this position is an ancestor of the other position
     */
    public boolean isAncestorOf(Position other) {
        if (this.path.size() >= other.path.size()) {
            return false;
        }

        for (int i = 0; i < this.path.size(); i++) {
            if (!this.path.get(i).equals(other.path.get(i))) {
                return false;
            }
        }

        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Position position = (Position) o;

        if (this.path.size() != position.path.size()) {
            return false;
        }

        for (int i = 0; i < this.path.size(); i++) {
            if (!this.path.get(i).equals(position.path.get(i))) {
                return false;
            }
        }

        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(path);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(path.get(i));
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public int compareTo(Position other) {
        int minLength = Math.min(this.path.size(), other.path.size());

        for (int i = 0; i < minLength; i++) {
            int comp = this.path.get(i).compareTo(other.path.get(i));
            if (comp != 0) {
                return comp;
            }
        }

        return Integer.compare(this.path.size(), other.path.size());
    }
}
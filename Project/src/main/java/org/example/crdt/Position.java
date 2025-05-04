package org.example.crdt;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public class Position implements Serializable, Comparable<Position> {

    private static final long serialVersionUID = 1L;

    // Global counter to ensure monotonically increasing values for consecutive inserts
    private static final AtomicLong globalCounter = new AtomicLong(0);

    /**
     * A single identifier in the path.
     * Each identifier has a position value, a client ID for uniqueness, and a sequence number.
     */
    public static class Identifier implements Serializable, Comparable<Identifier> {
        private static final long serialVersionUID = 1L;

        private final int position;
        private final String clientId;
        private final long sequence; // Added sequence for resolving rapid insertions

        /**
         * Creates a new identifier
         *
         * @param position The numeric position value
         * @param clientId The client ID for uniqueness
         * @param sequence The sequence number for ordering rapid insertions
         */
        public Identifier(int position, String clientId, long sequence) {
            this.position = position;
            this.clientId = clientId;
            this.sequence = sequence;
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

        /**
         * Gets the sequence number
         *
         * @return The sequence number
         */
        public long getSequence() {
            return sequence;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Identifier that = (Identifier) o;
            return position == that.position &&
                    sequence == that.sequence &&
                    Objects.equals(clientId, that.clientId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(position, clientId, sequence);
        }

        @Override
        public String toString() {
            return position + ":" + clientId + ":" + sequence;
        }

        @Override
        public int compareTo(Identifier other) {
            // First compare by position
            if (this.position != other.position) {
                return Integer.compare(this.position, other.position);
            }

            // If positions are equal, compare by client ID
            // This is important for ensuring consistent ordering across clients
            int clientCompare = this.clientId.compareTo(other.clientId);
            if (clientCompare != 0) {
                return clientCompare;
            }

            // If both position and clientId are equal, compare by sequence
            // This ensures consistent ordering for rapid typing from the same client
            return Long.compare(this.sequence, other.sequence);
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
        return Collections.unmodifiableList(path);  // Making path unmodifiable
    }

    /**
     * Creates a new position by appending an identifier to this position's path
     *
     * @param pos The position value for the new identifier
     * @param clientId The client ID for the new identifier
     * @return A new Position with the appended identifier
     */
    public Position append(int pos, String clientId) {
        return append(pos, clientId, globalCounter.incrementAndGet());
    }

    /**
     * Creates a new position by appending an identifier to this position's path with a specific sequence number
     *
     * @param pos The position value for the new identifier
     * @param clientId The client ID for the new identifier
     * @param sequence The sequence number for ordering
     * @return A new Position with the appended identifier
     */
    public Position append(int pos, String clientId, long sequence) {
        Position newPos = new Position(this.path);
        newPos.path.add(new Identifier(pos, clientId, sequence));
        return newPos;
    }

    /**
     * Generates a position between two existing positions
     * This is crucial for inserting characters between existing ones
     *
     * @param before The position before the insertion point (can be null for start)
     * @param after The position after the insertion point (can be null for end)
     * @param clientId The client ID generating the new position
     * @return A new position between the before and after positions
     */
    /**
     * Generates a position for a new character inserted at the end of the document
     * This should be used for normal typing at the cursor position
     *
     * @param lastPosition The position of the last character before insertion
     * @param clientId The client ID generating the new position
     * @return A new position after the last position
     */
    public static Position generatePositionForAppend(Position lastPosition, String clientId) {
        if (lastPosition == null) {
            // First character in the document
            return new Position().append(0, clientId);
        }

        // For appending, we always want to ensure the new position is strictly greater
        // Clone the path from the last position
        List<Identifier> newPath = new ArrayList<>(lastPosition.getPath());

        // Replace the last identifier with one that has a higher sequence number
        if (!newPath.isEmpty()) {
            Identifier lastId = newPath.remove(newPath.size() - 1);
            // Use the same position value but with a higher sequence number
            newPath.add(new Identifier(
                    lastId.getPosition(),
                    clientId,
                    globalCounter.incrementAndGet()
            ));
        } else {
            // Empty path, create first identifier
            newPath.add(new Identifier(0, clientId, globalCounter.incrementAndGet()));
        }

        return new Position(newPath);
    }

    /**
     * Generates a position between two existing positions
     * This is used for inserting characters between existing ones
     *
     * @param before The position before the insertion point (can be null for start)
     * @param after The position after the insertion point (can be null for end)
     * @param clientId The client ID generating the new position
     * @return A new position between the before and after positions
     */
    public static Position generatePositionBetween(Position before, Position after, String clientId) {
        // Base cases
        if (before == null && after == null) {
            return new Position().append(0, clientId);
        }

        if (before == null) {
            // Insert at beginning
            int firstPos = after.path.get(0).getPosition();
            if (firstPos > 0) {
                return new Position().append(firstPos - 1, clientId);
            } else {
                // If the first position is already 0, insert with a smaller sequence
                return new Position().append(0, clientId, -1); // Use negative sequence to ensure it comes before
            }
        }

        if (after == null) {
            // Insert at end - use the append function for consistency
            return generatePositionForAppend(before, clientId);
        }

        // For normal cases, we need to ensure strict ordering
        // Increase spacing between identifiers to avoid bunching

        // Strategy: Create a position that is lexicographically between before and after
        // by using a very large position step

        // Find the depth where the paths diverge
        int commonPrefixLength = 0;
        int minLength = Math.min(before.path.size(), after.path.size());

        while (commonPrefixLength < minLength &&
                before.path.get(commonPrefixLength).compareTo(after.path.get(commonPrefixLength)) == 0) {
            commonPrefixLength++;
        }

        // Case: One path is a prefix of the other
        if (commonPrefixLength == minLength) {
            if (before.path.size() == minLength) {
                // Before is a prefix of after
                List<Identifier> newPath = new ArrayList<>(before.getPath());
                newPath.add(new Identifier(0, clientId, globalCounter.incrementAndGet()));
                return new Position(newPath);
            } else {
                // After is a prefix of before
                Identifier afterNextId = after.path.get(commonPrefixLength);
                List<Identifier> newPath = new ArrayList<>(after.getPath().subList(0, commonPrefixLength));
                // Add an identifier that comes before the next one in after
                newPath.add(new Identifier(
                        afterNextId.getPosition() - 1 > 0 ? afterNextId.getPosition() - 1 : afterNextId.getPosition(),
                        clientId,
                        afterNextId.getSequence() - 1 > 0 ? afterNextId.getSequence() - 1 : 0
                ));
                return new Position(newPath);
            }
        }

        // Case: Paths diverge at some point
        if (commonPrefixLength < before.path.size() && commonPrefixLength < after.path.size()) {
            Identifier beforeId = before.path.get(commonPrefixLength);
            Identifier afterId = after.path.get(commonPrefixLength);

            List<Identifier> newPath = new ArrayList<>(before.getPath().subList(0, commonPrefixLength));

            if (beforeId.compareTo(afterId) < 0) {
                // If there's significant room between positions
                if (afterId.getPosition() - beforeId.getPosition() > 1) {
                    // Create a position with value in between
                    int newPos = beforeId.getPosition() + 1;
                    newPath.add(new Identifier(newPos, clientId, globalCounter.incrementAndGet()));
                } else if (afterId.getSequence() - beforeId.getSequence() > 1) {
                    // Same position but room between sequences
                    long newSeq = beforeId.getSequence() + 1;
                    newPath.add(new Identifier(beforeId.getPosition(), clientId, newSeq));
                } else {
                    // Very close positions, ensure ordering with global counter
                    newPath.add(new Identifier(
                            beforeId.getPosition(),
                            clientId,
                            globalCounter.incrementAndGet()
                    ));
                }

                return new Position(newPath);
            }
        }

        // Fallback: just create a position with a high sequence number
        // This ensures it won't disrupt existing order
        return generatePositionForAppend(before, clientId);
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
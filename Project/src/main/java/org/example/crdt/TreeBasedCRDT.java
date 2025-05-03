package org.example.crdt;

import java.util.*;

/**
 * A tree-based CRDT implementation for a collaborative text editor.
 * This CRDT uses a tree structure to maintain character positions and
 * ensure consistent document state across distributed clients.
 */
public class TreeBasedCRDT {
    // The base character to position map
    private final TreeMap<Position, Character> characters;
    // The current document content as a string (for faster access)
    private String cachedContent;
    // The client ID that owns this CRDT instance
    private final String clientId;
    // Strategy value for position generation
    private static final int STRATEGY_BOUNDARY = 10;

    /**
     * Creates a new tree-based CRDT with the specified client ID
     *
     * @param clientId The unique client identifier
     */
    public TreeBasedCRDT(String clientId) {
        this.characters = new TreeMap<>();
        this.clientId = clientId;
        this.cachedContent = "";
    }

    /**
     * Inserts a character at the specified index position
     *
     * @param index The index to insert at
     * @param c     The character to insert
     * @return The operation representing this insertion
     */
    public Operation insert(int index, char c) {
        Position position = generatePositionBetween(index);
        characters.put(position, c);
        rebuildCache();

        return new Operation(
                Operation.Type.INSERT,
                String.valueOf(c),
                positionToPathList(position),
                System.currentTimeMillis(),
                clientId
        );
    }

    /**
     * Deletes a character at the specified index position
     *
     * @param index The index to delete from
     * @return The operation representing this deletion
     */
    public Operation delete(int index) {
        if (index < 0 || index >= cachedContent.length()) {
            throw new IndexOutOfBoundsException("Index out of bounds: " + index);
        }

        Position position = getPositionAt(index);
        char deletedChar = characters.remove(position);
        rebuildCache();

        return new Operation(
                Operation.Type.DELETE,
                String.valueOf(deletedChar),
                positionToPathList(position),
                System.currentTimeMillis(),
                clientId
        );
    }

    /**
     * Applies a remote operation to this CRDT
     *
     * @param operation The operation to apply
     * @return True if the operation was successfully applied
     */
    public boolean applyOperation(Operation operation) {
        Position position = pathListToPosition(operation.getPath());

        if (operation.getType() == Operation.Type.INSERT) {
            if (operation.getContent().length() != 1) {
                // For simplicity, we only handle single character operations
                return false;
            }
            characters.put(position, operation.getContent().charAt(0));
            rebuildCache();
            return true;
        } else if (operation.getType() == Operation.Type.DELETE) {
            if (characters.containsKey(position)) {
                characters.remove(position);
                rebuildCache();
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the current document content
     *
     * @return The document content as a string
     */
    public String getContent() {
        return cachedContent;
    }

    /**
     * Rebuilds the cached content string from the characters map
     */
    private void rebuildCache() {
        StringBuilder sb = new StringBuilder();
        for (Character c : characters.values()) {
            sb.append(c);
        }
        this.cachedContent = sb.toString();
    }

    /**
     * Generates a position between the positions at index-1 and index
     *
     * @param index The index to generate a position for
     * @return The new position
     */
    private Position generatePositionBetween(int index) {
        if (index < 0 || index > cachedContent.length()) {
            throw new IndexOutOfBoundsException("Index out of bounds: " + index);
        }

        Position before = null;
        Position after = null;

        if (index == 0) {
            // Special case: inserting at the beginning
            if (characters.isEmpty()) {
                return new Position().append(STRATEGY_BOUNDARY, clientId);
            } else {
                after = characters.firstKey();
            }
        } else if (index == cachedContent.length()) {
            // Special case: inserting at the end
            before = characters.lastKey();
        } else {
            // Inserting in the middle
            List<Position> positions = new ArrayList<>(characters.keySet());
            before = positions.get(index - 1);
            after = positions.get(index);
        }

        return generatePositionBetween(before, after);
    }

    /**
     * Generates a position between two positions
     *
     * @param before The position before
     * @param after  The position after (may be null)
     * @return A new position between the two
     */
    private Position generatePositionBetween(Position before, Position after) {
        if (before == null && after == null) {
            // Create a new position with a default value
            return new Position().append(STRATEGY_BOUNDARY, clientId);
        }

        if (before == null) {
            // Generate a position before the first position
            int pos = after.getPath().get(0).getPosition() / 2;
            return new Position().append(pos, clientId);
        }

        if (after == null) {
            // Generate a position after the last position
            int pos = before.getPath().get(0).getPosition() + STRATEGY_BOUNDARY;
            return new Position().append(pos, clientId);
        }

        // Find the common ancestor path
        List<Position.Identifier> beforePath = before.getPath();
        List<Position.Identifier> afterPath = after.getPath();

        int minLength = Math.min(beforePath.size(), afterPath.size());
        int divergeIndex = 0;

        while (divergeIndex < minLength &&
                beforePath.get(divergeIndex).equals(afterPath.get(divergeIndex))) {
            divergeIndex++;
        }

        if (divergeIndex < minLength) {
            // Different identifiers at divergeIndex
            Position.Identifier beforeId = beforePath.get(divergeIndex);
            Position.Identifier afterId = afterPath.get(divergeIndex);

            if (afterId.getPosition() - beforeId.getPosition() > 1) {
                // There's room between the values
                int pos = beforeId.getPosition() +
                        (afterId.getPosition() - beforeId.getPosition()) / 2;

                // Create new position with shared prefix and new identifier
                Position newPos = new Position();
                for (int i = 0; i < divergeIndex; i++) {
                    newPos = newPos.append(
                            beforePath.get(i).getPosition(),
                            beforePath.get(i).getClientId()
                    );
                }
                return newPos.append(pos, clientId);
            }
        }

        // Either paths are identical up to one's end, or consecutive positions
        // Append to the longer path or to before if they're the same length
        Position newPos = new Position(new ArrayList<>(beforePath));
        return newPos.append(STRATEGY_BOUNDARY, clientId);
    }

    /**
     * Gets the position at the specified index
     *
     * @param index The index to get the position for
     * @return The position at the index
     */
    private Position getPositionAt(int index) {
        if (index < 0 || index >= cachedContent.length()) {
            throw new IndexOutOfBoundsException("Index out of bounds: " + index);
        }

        List<Position> positions = new ArrayList<>(characters.keySet());
        return positions.get(index);
    }

    /**
     * Converts a path list from the Operation to a Position object
     *
     * @param pathList The path list from the operation
     * @return The corresponding Position object
     */
    private Position pathListToPosition(List<String> pathList) {
        // This is a sample implementation - you should adapt this
        // to fit your specific path encoding strategy
        Position position = new Position();

        for (String pathElement : pathList) {
            if (pathElement.contains(":")) {
                String[] parts = pathElement.split(":");
                int pos = Integer.parseInt(parts[0]);
                String id = parts[1];
                position = position.append(pos, id);
            }
        }

        return position;
    }

    /**
     * Converts a Position object to a path list for an Operation
     *
     * @param position The position to convert
     * @return The path list for the operation
     */
    private List<String> positionToPathList(Position position) {
        // This is a sample implementation - you should adapt this
        // to fit your specific path encoding strategy
        List<String> pathList = new ArrayList<>();

        for (Position.Identifier identifier : position.getPath()) {
            pathList.add(identifier.getPosition() + ":" + identifier.getClientId());
        }

        return pathList;
    }
}
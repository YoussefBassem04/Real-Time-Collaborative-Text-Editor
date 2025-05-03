package org.example.crdt;

import java.util.*;

public class TreeBasedCRDT {
    final Map<Position, Character> characters;
    final List<Position> positionOrder;
    private String cachedContent;
    private final String clientId;
    private static final int STRATEGY_BOUNDARY = 100;
    private long operationCounter = 0;

    public TreeBasedCRDT(String clientId) {
        this.characters = new LinkedHashMap<>();
        this.positionOrder = new ArrayList<>();
        this.clientId = clientId;
        this.cachedContent = "";
    }

    public Operation insert(int index, char c) {
        if (index < 0 || index > cachedContent.length()) {
            throw new IndexOutOfBoundsException("Index out of bounds: " + index);
        }

        Position before = index > 0 ? positionOrder.get(index - 1) : null;
        Position after = index < positionOrder.size() ? positionOrder.get(index) : null;

        long opId = ++operationCounter;
        Position newPosition = generatePositionBetween(before, after, opId);

        positionOrder.add(index, newPosition);
        characters.put(newPosition, c);
        rebuildCache();

        return new Operation(
                Operation.Type.INSERT,
                String.valueOf(c),
                positionToPathList(newPosition),
                System.currentTimeMillis(),
                clientId
        );
    }

    public Operation delete(int index) {
        if (index < 0 || index >= cachedContent.length()) {
            throw new IndexOutOfBoundsException("Index out of bounds: " + index);
        }

        Position position = positionOrder.get(index);
        char deletedChar = characters.remove(position);
        positionOrder.remove(index);
        rebuildCache();

        return new Operation(
                Operation.Type.DELETE,
                String.valueOf(deletedChar),
                positionToPathList(position),
                System.currentTimeMillis(),
                clientId
        );
    }

    public boolean applyOperation(Operation operation) {
        Position position = pathListToPosition(operation.getPath());

        if (operation.getType() == Operation.Type.INSERT) {
            if (operation.getContent().length() != 1) {
                return false;
            }

            if (characters.containsKey(position)) {
                return false;
            }

            int insertIndex = findInsertionIndex(position);
            positionOrder.add(insertIndex, position);
            characters.put(position, operation.getContent().charAt(0));
            rebuildCache();
            debugPrintStructure();
            return true;
        } else if (operation.getType() == Operation.Type.DELETE) {
            if (characters.containsKey(position)) {
                int deleteIndex = positionOrder.indexOf(position);
                if (deleteIndex >= 0) {
                    positionOrder.remove(deleteIndex);
                    characters.remove(position);
                    rebuildCache();
                    debugPrintStructure();
                    return true;
                }
            }
        }
        return false;
    }

    private int findInsertionIndex(Position position) {
        if (positionOrder.isEmpty()) {
            return 0;
        }

        int low = 0;
        int high = positionOrder.size() - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            int midIndex = mid;
            Position midPos = positionOrder.get(midIndex);
            int cmp = position.compareTo(midPos);

            if (cmp < 0) {
                high = mid - 1;
            } else if (cmp > 0) {
                low = mid + 1;
            } else {
                return mid;
            }
        }

        return low;
    }

    public String getContent() {
        return cachedContent;
    }

    private void rebuildCache() {
        StringBuilder sb = new StringBuilder();
        for (Position pos : positionOrder) {
            sb.append(characters.get(pos));
        }
        this.cachedContent = sb.toString();
    }

    private Position generatePositionBetween(Position before, Position after, long opId) {
        Position newPos = new Position();
        String uniqueId = clientId + ":" + opId;

        if (before == null && after == null) {
            // Empty document
            return newPos.append(STRATEGY_BOUNDARY, uniqueId);
        }

        if (before == null) {
            // Insert at start
            int afterPos = after.getPath().get(0).getPosition();
            if (afterPos > 1) {
                return newPos.append(afterPos / 2, uniqueId);
            } else {
                return newPos.append(0, uniqueId);
            }
        }

        if (after == null) {
            // Insert at end
            int beforePos = before.getPath().get(before.getPath().size() - 1).getPosition();
            return newPos.append(beforePos + STRATEGY_BOUNDARY, uniqueId);
        }

        // Insert between two positions
        List<Position.Identifier> beforePath = before.getPath();
        List<Position.Identifier> afterPath = after.getPath();
        int commonPrefix = 0;
        int minLength = Math.min(beforePath.size(), afterPath.size());

        while (commonPrefix < minLength &&
                beforePath.get(commonPrefix).getPosition() == afterPath.get(commonPrefix).getPosition()) {
            commonPrefix++;
        }

        // Copy common prefix
        for (int i = 0; i < commonPrefix; i++) {
            Position.Identifier id = beforePath.get(i);
            newPos = newPos.append(id.getPosition(), id.getClientId());
        }

        if (commonPrefix < beforePath.size() && commonPrefix < afterPath.size()) {
            // Paths diverge
            int beforePos = beforePath.get(commonPrefix).getPosition();
            int afterPos = afterPath.get(commonPrefix).getPosition();
            if (afterPos - beforePos > 1) {
                // Room to insert between
                int newPosValue = beforePos + (afterPos - beforePos) / 2;
                return newPos.append(newPosValue, uniqueId);
            } else {
                // No room, extend path
                return newPos.append(beforePos + 1, uniqueId);
            }
        } else if (beforePath.size() > commonPrefix) {
            // Before path is longer
            int beforePos = beforePath.get(commonPrefix).getPosition();
            return newPos.append(beforePos + 1, uniqueId);
        } else if (afterPath.size() > commonPrefix) {
            // After path is longer
            int afterPos = afterPath.get(commonPrefix).getPosition();
            if (afterPos > 1) {
                return newPos.append(afterPos / 2, uniqueId);
            } else {
                return newPos.append(0, uniqueId);
            }
        } else {
            // Paths are equal up to common prefix, extend with new position
            return newPos.append(STRATEGY_BOUNDARY, uniqueId);
        }
    }

    private Position copyPathUntil(List<Position.Identifier> path, int length) {
        Position newPos = new Position();
        for (int i = 0; i < Math.min(length, path.size()); i++) {
            Position.Identifier id = path.get(i);
            newPos = newPos.append(id.getPosition(), id.getClientId());
        }
        return newPos;
    }

    private Position pathListToPosition(List<String> pathList) {
        Position position = new Position();

        for (String pathElement : pathList) {
            String[] parts = pathElement.split(":", 2);
            if (parts.length == 2) {
                try {
                    int pos = Integer.parseInt(parts[0]);
                    String id = parts[1];
                    position = position.append(pos, id);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid path format: " + pathElement);
                }
            }
        }

        return position;
    }

    private List<String> positionToPathList(Position position) {
        List<String> pathList = new ArrayList<>();
        for (Position.Identifier identifier : position.getPath()) {
            pathList.add(identifier.getPosition() + ":" + identifier.getClientId());
        }
        return pathList;
    }

    public void debugPrintStructure() {
        System.out.println("CRDT Structure:");
        System.out.println("Content: \"" + cachedContent + "\"");
        System.out.println("Character positions:");
        for (int i = 0; i < positionOrder.size(); i++) {
            Position pos = positionOrder.get(i);
            char c = characters.get(pos);
            System.out.printf("%d: '%c' at position %s%n", i, c, pos);
        }
        System.out.println("---------------------");
    }
}
package org.example.crdt;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Collections;
/**
 * CRDT-based document implementation using a Logoot-like algorithm
 * for conflict-free collaborative editing.
 */
public class Document {
    private final TreeMap<Identifier, CharItem> charItems;
    private final int siteId;
    private int clock;

    public Document(int siteId) {
        this.siteId = siteId;
        this.clock = 0;
        this.charItems = new TreeMap<>();
    }

    /**
     * Generates a new identifier between two existing identifiers
     */
    private Identifier generateIdBetween(Identifier id1, Identifier id2, int depth) {
        List<Integer> newDigits = new ArrayList<>();

        // Copy common prefix
        int i = 0;
        while (i < id1.getDigits().size() && i < id2.getDigits().size() &&
                id1.getDigits().get(i).equals(id2.getDigits().get(i))) {
            newDigits.add(id1.getDigits().get(i));
            i++;
        }

        // Handle remaining digits
        if (i < id1.getDigits().size()) {
            int digit1 = i < id1.getDigits().size() ? id1.getDigits().get(i) : 0;
            int digit2 = i < id2.getDigits().size() ? id2.getDigits().get(i) : 0;

            if (digit2 - digit1 > 1) {
                // Can fit a digit between them
                int newDigit = digit1 + (digit2 - digit1) / 2;
                newDigits.add(newDigit);
            } else {
                // Need to add another level
                newDigits.add(digit1);
                newDigits.add(0);
                while (digit2 == digit1 + 1 && i + 1 < id1.getDigits().size() && i + 1 < id2.getDigits().size()) {
                    i++;
                    digit1 = id1.getDigits().get(i);
                    digit2 = id2.getDigits().get(i);
                    newDigits.add(0);
                }
            }
        } else {
            newDigits.add(0);
        }

        return new Identifier(newDigits, siteId, ++clock);
    }

    /**
     * Gets the current text content of the document
     */
    public String getText() {
        StringBuilder sb = new StringBuilder();
        for (CharItem charItem : charItems.values()) {
            if (charItem.isVisible()) {
                sb.append(charItem.getValue());
            }
        }
        return sb.toString();
    }

    /**
     * Applies a remote operation to the document
     */
    public synchronized void applyOperation(Operation operation) {
        CharItem charItem = operation.getCharItem();
        Identifier id = charItem.getId();

        switch (operation.getType()) {
            case INSERT:
                charItems.put(id, charItem);
                break;
            case DELETE:
                CharItem existing = charItems.get(id);
                if (existing != null) {
                    existing.setVisible(false);
                }
                break;
        }
    }

    /**
     * Generates an insert operation for a local edit
     */
    public synchronized Operation localInsert(int position, char value) {
        if (position < 0 || position > getText().length()) {
            throw new IndexOutOfBoundsException("Invalid insert position");
        }

        // Find neighbors for positioning
        Identifier prevId = position == 0 ? null : getIdentifierAt(position - 1);
        Identifier nextId = position == getText().length() ? null : getIdentifierAt(position);

        // Generate new identifier between neighbors
        Identifier newId = generateIdBetween(
                prevId != null ? prevId : new Identifier(new ArrayList<>(), -1, 0),
                nextId != null ? nextId : new Identifier(new ArrayList<>(), -1, 0),
                0
        );

        // Create new character item
        CharItem charItem = new CharItem(value, newId, true);
        charItems.put(newId, charItem);

        return new Operation(Operation.Type.INSERT, charItem);
    }

    /**
     * Generates a delete operation for a local edit
     */
    public synchronized Operation localDelete(int position) {
        if (position < 0 || position >= getText().length()) {
            throw new IndexOutOfBoundsException("Invalid delete position");
        }

        Identifier id = getIdentifierAt(position);
        CharItem charItem = charItems.get(id);
        if (charItem != null) {
            charItem.setVisible(false);
            return new Operation(Operation.Type.DELETE, charItem);
        }
        return null;
    }

    /**
     * Gets the identifier at a specific position in the visible text
     */
    private Identifier getIdentifierAt(int position) {
        int currentPos = 0;
        for (CharItem charItem : charItems.values()) {
            if (charItem.isVisible()) {
                if (currentPos == position) {
                    return charItem.getId();
                }
                currentPos++;
            }
        }
        throw new IndexOutOfBoundsException("Position not found");
    }

    /**
     * Gets the current state of the document for synchronization
     */
    public synchronized List<CharItem> getState() {
        return new ArrayList<>(charItems.values());
    }

    /**
     * Merges a remote state into the current document
     */
    public synchronized void mergeState(List<CharItem> remoteState) {
        for (CharItem remoteItem : remoteState) {
            CharItem localItem = charItems.get(remoteItem.getId());
            if (localItem == null) {
                charItems.put(remoteItem.getId(), remoteItem);
            } else if (remoteItem.getId().compareTo(localItem.getId()) > 0) {
                // If remote item has higher priority (newer), use it
                charItems.put(remoteItem.getId(), remoteItem);
            }
        }
    }
}

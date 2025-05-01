package org.example.crdt;

import java.util.Objects;
/**
 * Represents a character in the document with its metadata.
 * Immutable except for the visible flag which can be toggled for deletion.
 */
public class CharItem {
    private final char value;
    private final Identifier id;
    private volatile boolean visible;

    public CharItem(char value, Identifier id, boolean visible) {
        if (id == null) {
            throw new IllegalArgumentException("Identifier cannot be null");
        }
        this.value = value;
        this.id = id;
        this.visible = visible;
    }

    public char getValue() {
        return value;
    }

    public Identifier getId() {
        return id;
    }

    public boolean isVisible() {
        return visible;
    }

    public synchronized void setVisible(boolean visible) {
        this.visible = visible;
    }

    /**
     * Creates a new CharItem with the same properties but different visibility
     */
    public CharItem withVisibility(boolean visible) {
        return new CharItem(value, id, visible);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CharItem charItem = (CharItem) o;
        return value == charItem.value &&
                visible == charItem.visible &&
                id.equals(charItem.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, id, visible);
    }

    @Override
    public String toString() {
        return String.format("%s(%c,%b)", id.toString(), value, visible);
    }
}
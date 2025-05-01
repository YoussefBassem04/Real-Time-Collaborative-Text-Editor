package org.example.crdt;

import java.util.Objects;
/**
 * Represents an edit operation on the document.
 * Immutable data structure.
 */
public class Operation {
    public enum Type { INSERT, DELETE }

    private final Type type;
    private final CharItem charItem;

    public Operation(Type type, CharItem charItem) {
        if (type == null || charItem == null) {
            throw new IllegalArgumentException("Operation type and charItem cannot be null");
        }
        this.type = type;
        this.charItem = charItem;
    }

    public Type getType() {
        return type;
    }

    public CharItem getCharItem() {
        return charItem;
    }

    /**
     * Creates a new operation with the same type but updated charItem visibility
     */
    public Operation withCharItemVisibility(boolean visible) {
        return new Operation(type, charItem.withVisibility(visible));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Operation operation = (Operation) o;
        return type == operation.type &&
                charItem.equals(operation.charItem);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, charItem);
    }

    @Override
    public String toString() {
        return String.format("%s[%s]", type.name(), charItem.toString());
    }
}
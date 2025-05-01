package org.example.crdt;

public class Operation {
    public enum Type { INSERT, DELETE }

    public final Type type;
    public final String id;
    public final char value;
    public final String parentId;

    public Operation(Type type, String id, char value, String parentId) {
        this.type = type;
        this.id = id;
        this.value = value;
        this.parentId = parentId;
    }
}

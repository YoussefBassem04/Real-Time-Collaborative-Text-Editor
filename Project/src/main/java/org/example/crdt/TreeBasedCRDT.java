package org.example.crdt;

import java.util.*;

public class TreeBasedCRDT {
    public static class CharNode {
        public final String id;
        public final char value;
        public final String parentId;
        public final List<String> children;
        public boolean deleted;

        public CharNode(String id, char value, String parentId) {
            this.id = id;
            this.value = value;
            this.parentId = parentId;
            this.children = new ArrayList<>();
            this.deleted = false;
        }
    }

    private final Map<String, CharNode> nodes = new HashMap<>();
    private final String rootId = "root";
    private int logicalClock = 0;
    private final Stack<Operation> undoStack = new Stack<>();
    private final Stack<Operation> redoStack = new Stack<>();

    public TreeBasedCRDT() {
        nodes.put(rootId, new CharNode(rootId, '\0', null));
    }

    private String generateId(String userId) {
        return userId + ":" + (logicalClock++);
    }

    public Operation insert(String userId, char value, String parentId) {
        String id = generateId(userId);
        CharNode parent = nodes.getOrDefault(parentId, nodes.get(rootId));
        CharNode newNode = new CharNode(id, value, parent.id);
        parent.children.add(id);
        nodes.put(id, newNode);
        Operation op = new Operation(Operation.Type.INSERT, id, value, parent.id);
        undoStack.push(op);
        redoStack.clear();
        return op;
    }

    public Operation delete(String id) {
        CharNode node = nodes.get(id);
        if (node != null && !node.deleted) {
            node.deleted = true;
            Operation op = new Operation(Operation.Type.DELETE, id, node.value, node.parentId);
            undoStack.push(op);
            redoStack.clear();
            return op;
        }
        return null;
    }

    public void apply(Operation op) {
        switch (op.type) {
            case INSERT:
                if (!nodes.containsKey(op.id)) {
                    CharNode parent = nodes.getOrDefault(op.parentId, nodes.get(rootId));
                    CharNode newNode = new CharNode(op.id, op.value, op.parentId);
                    parent.children.add(op.id);
                    nodes.put(op.id, newNode);
                }
                break;
            case DELETE:
                CharNode node = nodes.get(op.id);
                if (node != null) node.deleted = true;
                break;
        }
    }

    public void undo() {
        if (!undoStack.isEmpty()) {
            Operation op = undoStack.pop();
            redoStack.push(op);
            if (op.type == Operation.Type.INSERT) {
                nodes.get(op.id).deleted = true;
            } else if (op.type == Operation.Type.DELETE) {
                nodes.get(op.id).deleted = false;
            }
        }
    }

    public void redo() {
        if (!redoStack.isEmpty()) {
            Operation op = redoStack.pop();
            undoStack.push(op);
            if (op.type == Operation.Type.INSERT) {
                nodes.get(op.id).deleted = false;
            } else if (op.type == Operation.Type.DELETE) {
                nodes.get(op.id).deleted = true;
            }
        }
    }

    public String getText() {
        StringBuilder sb = new StringBuilder();
        traverse(rootId, sb);
        return sb.toString();
    }
    public List<String> getNodeIds() {
        List<String> ids = new ArrayList<>();
        collectVisibleIds(rootId, ids);
        return ids;
    }

    private void collectVisibleIds(String nodeId, List<String> ids) {
        for (String childId : nodes.get(nodeId).children) {
            CharNode child = nodes.get(childId);
            if (!child.deleted) {
                ids.add(child.id);
            }
            collectVisibleIds(childId, ids);
        }
    }

    private void traverse(String nodeId, StringBuilder sb) {
        for (String childId : nodes.get(nodeId).children) {
            CharNode child = nodes.get(childId);
            if (!child.deleted) {
                sb.append(child.value);
            }
            traverse(childId, sb);
        }
    }
}

package org.server.core;

import java.util.*;

public class TreeBasedCRDT {
    private static class Node {
        char value;
        String id;
        String parentId;
        List<String> childrenIds;
        boolean isDeleted;

        Node(char value, String id, String parentId) {
            this.value = value;
            this.id = id;
            this.parentId = parentId;
            this.childrenIds = new ArrayList<>();
            this.isDeleted = false;
        }
    }

    private static class Operation {
        String type; // "insert" or "delete"
        String nodeId;
        char value;
        String parentId;
        String referenceId;

        Operation(String type, String nodeId, char value, String parentId, String referenceId) {
            this.type = type;
            this.nodeId = nodeId;
            this.value = value;
            this.parentId = parentId;
            this.referenceId = referenceId;
        }
    }

    private Map<String, Node> nodes;
    private String rootId;
    private Random random;
    private Stack<Operation> undoStack;
    private Stack<Operation> redoStack;

    public TreeBasedCRDT() {
        this.nodes = new HashMap<>();
        this.random = new Random();
        this.rootId = generateId();
        this.undoStack = new Stack<>();
        this.redoStack = new Stack<>();
        Node root = new Node('\0', rootId, null);
        nodes.put(rootId, root);
    }

    private String generateId() {
        return UUID.randomUUID().toString();
    }

    public void insert(char value, String parentId, String referenceId) {
        String newNodeId = generateId();
        Node newNode = new Node(value, newNodeId, parentId);
        nodes.put(newNodeId, newNode);

        Node parent = nodes.get(parentId);
        if (parent != null) {
            if (referenceId == null) {
                parent.childrenIds.add(newNodeId);
            } else {
                int index = parent.childrenIds.indexOf(referenceId);
                if (index != -1) {
                    parent.childrenIds.add(index, newNodeId);
                } else {
                    parent.childrenIds.add(newNodeId);
                }
            }
        }

        // Record operation for undo
        undoStack.push(new Operation("insert", newNodeId, value, parentId, referenceId));
        redoStack.clear(); // Clear redo stack when new operation is performed
    }

    public void delete(String nodeId) {
        Node node = nodes.get(nodeId);
        if (node != null) {
            node.isDeleted = true;
            // Record operation for undo
            undoStack.push(new Operation("delete", nodeId, node.value, node.parentId, null));
            redoStack.clear(); // Clear redo stack when new operation is performed
        }
    }

    public String getText() {
        StringBuilder result = new StringBuilder();
        buildText(rootId, result);
        return result.toString();
    }

    private void buildText(String nodeId, StringBuilder result) {
        Node node = nodes.get(nodeId);
        if (node != null) {
            if (!node.isDeleted && node.value != '\0') {
                result.append(node.value);
            }
            for (String childId : node.childrenIds) {
                buildText(childId, result);
            }
        }
    }


    public void undo() {
        if (!undoStack.isEmpty()) {
            Operation op = undoStack.pop();
            if (op.type.equals("insert")) {
                // Undo insert by deleting the node
                Node node = nodes.get(op.nodeId);
                if (node != null) {
                    node.isDeleted = true;
                    redoStack.push(new Operation("insert", op.nodeId, op.value, op.parentId, op.referenceId));
                }
            } else if (op.type.equals("delete")) {
                // Undo delete by restoring the node
                Node node = nodes.get(op.nodeId);
                if (node != null) {
                    node.isDeleted = false;
                    redoStack.push(new Operation("delete", op.nodeId, op.value, op.parentId, null));
                }
            }
        }
    }

    public void redo() {
        if (!redoStack.isEmpty()) {
            Operation op = redoStack.pop();
            Node node = nodes.get(op.nodeId);
            if (op.type.equals("insert") && node != null) {
                node.isDeleted = false;
                undoStack.push(new Operation("insert", op.nodeId, op.value, op.parentId, op.referenceId));
            } else if (op.type.equals("delete") && node != null) {
                node.isDeleted = true;
                undoStack.push(new Operation("delete", op.nodeId, op.value, op.parentId, null));
            }
        }
    }


    public List<String> getNodeIds() {
        List<String> ids = new ArrayList<>();
        for (Node node : nodes.values()) {
            if (!node.isDeleted) {
                ids.add(node.id);
            }
        }
        return ids;
    }

    public String getNodeValue(String nodeId) {
        Node node = nodes.get(nodeId);
        return node != null && !node.isDeleted ? String.valueOf(node.value) : null;
    }
    public String getRootId() {
        return rootId;
    }

}

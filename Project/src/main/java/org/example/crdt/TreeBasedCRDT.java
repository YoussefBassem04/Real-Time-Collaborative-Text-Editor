package org.example.crdt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TreeBasedCRDT {
    private TreeNode root;
    private String replicaId;

    public TreeBasedCRDT() {
        this.root = new TreeNode("root");
        this.replicaId = UUID.randomUUID().toString();
    }

    public synchronized void applyOperation(Operation operation) {
        List<String> fullPath = operation.getPath();
        List<String> parentPath = fullPath.subList(0, fullPath.size() - 1); // Trim last element

        TreeNode parent = findParentNode(parentPath);

        if (parent == null) {
            System.err.println("Could not find parent node for path: " + parentPath);
            return;
        }

        if (operation.getType() == Operation.Type.INSERT) {
            TreeNode newNode = new TreeNode(operation.getContent());
            newNode.setTimestamp(operation.getTimestamp());
            newNode.setClientId(operation.getClientId());
            parent.addChild(newNode);
        } else if (operation.getType() == Operation.Type.DELETE) {
            parent.removeChild(operation.getContent());
        }
    }


    public Operation generateInsertOp(String content, List<String> path, String clientId) {
        long timestamp = System.currentTimeMillis();
        return new Operation(Operation.Type.INSERT, content, path, timestamp, clientId);
    }

    public Operation generateDeleteOp(String content, List<String> path, String clientId) {
        long timestamp = System.currentTimeMillis();
        return new Operation(Operation.Type.DELETE, content, path, timestamp, clientId);
    }

    public String getText() {
        StringBuilder sb = new StringBuilder();
        traverseTree(root, sb);
        return sb.toString();
    }

    public Map<String, Object> getDocumentState() {
        Map<String, Object> state = new HashMap<>();
        state.put("text", getText());
        state.put("structure", getTreeStructure(root));
        return state;
    }

    private TreeNode findParentNode(List<String> path) {
        TreeNode current = root;

        for (String pathElement : path) {
            if (pathElement.equals("root")) continue; // skip redundant match to self

            boolean found = false;
            for (TreeNode child : current.getChildren()) {
                if (child.getValue().equals(pathElement)) {
                    current = child;
                    found = true;
                    break;
                }
            }
            if (!found) return null;
        }
        return current;
    }

    private void traverseTree(TreeNode node, StringBuilder sb) {
        if (!node.getValue().equals("root")) {
            sb.append(node.getValue());
        }
        for (TreeNode child : node.getChildren()) {
            traverseTree(child, sb);
        }
    }

    private Map<String, Object> getTreeStructure(TreeNode node) {
        Map<String, Object> nodeInfo = new HashMap<>();
        nodeInfo.put("value", node.getValue());
        nodeInfo.put("timestamp", node.getTimestamp());
        nodeInfo.put("clientId", node.getClientId());

        List<Map<String, Object>> children = new ArrayList<>();
        for (TreeNode child : node.getChildren()) {
            children.add(getTreeStructure(child));
        }

        if (!children.isEmpty()) {
            nodeInfo.put("children", children);
        }

        return nodeInfo;
    }

    private static class TreeNode implements Comparable<TreeNode> {
        private String value;
        private List<TreeNode> children;
        private long timestamp;
        private String clientId;

        public TreeNode(String value) {
            this.value = value;
            this.children = new ArrayList<>();
            this.timestamp = System.currentTimeMillis();
            this.clientId = UUID.randomUUID().toString();
        }

        public void addChild(TreeNode node) {
            children.add(node);
            children.sort(TreeNode::compareTo);
        }

        public void removeChild(String value) {
            children.removeIf(node -> node.getValue().equals(value));
        }

        @Override
        public int compareTo(TreeNode other) {
            if (this.timestamp != other.timestamp) {
                return Long.compare(this.timestamp, other.timestamp);
            }
            return this.clientId.compareTo(other.clientId);
        }

        public String getValue() { return value; }
        public List<TreeNode> getChildren() { return children; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
    }
}
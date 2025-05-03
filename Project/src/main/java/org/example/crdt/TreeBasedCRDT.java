package org.example.crdt;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TreeBasedCRDT {
    private TreeNode root;
    private String replicaId;
    private Map<String, TreeNode> nodeMap; // nodeId to TreeNode for O(1) lookup
    private Set<String> appliedOperationIds; // Track applied operations

    public TreeBasedCRDT() {
        this.root = new TreeNode("root");
        this.replicaId = UUID.randomUUID().toString();
        this.nodeMap = new ConcurrentHashMap<>();
        this.appliedOperationIds = Collections.synchronizedSet(new HashSet<>());
        this.nodeMap.put(root.getNodeId(), root);
    }

    public synchronized void applyOperation(Operation operation) {
        if (operation.getPath().isEmpty() || appliedOperationIds.contains(operation.getId())) {
            return; // Skip invalid or duplicate operations
        }

        List<String> fullPath = operation.getPath();
        String nodeId = fullPath.get(fullPath.size() - 1);
        List<String> parentPath = fullPath.subList(0, fullPath.size() - 1);
        TreeNode parent = findParentNode(parentPath);

        if (parent == null) {
            System.err.println("Could not find parent node for path: " + parentPath);
            return;
        }

        if (operation.getType() == Operation.Type.INSERT) {
            TreeNode newNode = new TreeNode(operation.getContent());
            newNode.setTimestamp(operation.getTimestamp());
            newNode.setClientId(operation.getClientId());
            newNode.setNodeId(nodeId);
            parent.addChild(newNode);
            nodeMap.put(nodeId, newNode);
        } else if (operation.getType() == Operation.Type.DELETE) {
            parent.removeChildById(nodeId);
            nodeMap.remove(nodeId);
        }

        appliedOperationIds.add(operation.getId());
    }

    public Operation generateInsertOp(String content, List<String> path, String clientId) {
        Operation op = new Operation(Operation.Type.INSERT, content, path, clientId);
        op.getVectorClock().put(clientId, op.getVectorClock().getOrDefault(clientId, 0) + 1);
        return op;
    }

    public Operation generateDeleteOp(String content, List<String> path, String clientId) {
        Operation op = new Operation(Operation.Type.DELETE, content, path, clientId);
        op.getVectorClock().put(clientId, op.getVectorClock().getOrDefault(clientId, 0) + 1);
        return op;
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
            if (pathElement.equals("root")) continue;
            current = nodeMap.get(pathElement);
            if (current == null) return null;
        }
        return current;
    }

    private void traverseTree(TreeNode node, StringBuilder sb) {
        if (!node.getValue().equals("root") && node.getValue() != null) {
            sb.append(node.getValue());
        }
        for (TreeNode child : node.getChildren()) {
            traverseTree(child, sb);
        }
    }

    private Map<String, Object> getTreeStructure(TreeNode node) {
        Map<String, Object> nodeInfo = new HashMap<>();
        nodeInfo.put("value", node.getValue());
        nodeInfo.put("nodeId", node.getNodeId());
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
        private String nodeId;
        private List<TreeNode> children;
        private long timestamp;
        private String clientId;

        public TreeNode(String value) {
            this.value = value;
            this.nodeId = UUID.randomUUID().toString();
            this.children = new ArrayList<>();
            this.timestamp = System.currentTimeMillis();
            this.clientId = UUID.randomUUID().toString();
        }

        public void addChild(TreeNode node) {
            children.add(node);
            children.sort(TreeNode::compareTo);
        }

        public void removeChildById(String nodeId) {
            children.removeIf(node -> node.getNodeId().equals(nodeId));
        }

        @Override
        public int compareTo(TreeNode other) {
            if (this.timestamp != other.timestamp) {
                return Long.compare(this.timestamp, other.timestamp);
            }
            return this.clientId.compareTo(other.clientId);
        }

        public String getValue() { return value; }
        public String getNodeId() { return nodeId; }
        public void setNodeId(String nodeId) { this.nodeId = nodeId; }
        public List<TreeNode> getChildren() { return children; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
    }
}
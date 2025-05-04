package org.example.crdt;

import java.util.*;

public class TreeBasedCRDT {
    private final TreeNode root;
    private final Map<String, TreeNode> nodeMap;
    private final String clientId;
    private long operationCounter = 0;

    public TreeBasedCRDT(String clientId) {
        this.clientId = clientId;
        this.nodeMap = new HashMap<>();
        this.root = new TreeNode("root", new Position(), null);
        this.nodeMap.put("root", root);
    }

    public Operation insert(int index, char c) {
        String nodeId = clientId + ":" + (++operationCounter);
        Position position = generatePositionForIndex(index);
        TreeNode newNode = new TreeNode(nodeId, position, c);
        
        TreeNode parent = findParentNode(position);
        insertNodeInOrder(parent, newNode);
        nodeMap.put(nodeId, newNode);

        Operation op = new Operation(
                Operation.Type.INSERT,
                String.valueOf(c),
                positionToPathList(position),
                System.currentTimeMillis(),
                clientId
        );
        op.setNodeId(nodeId);
        return op;
    }

    public Operation delete(int index) {
        TreeNode nodeToDelete = findNodeAtIndex(index);
        if (nodeToDelete == null) {
            throw new IndexOutOfBoundsException("Index out of bounds: " + index);
        }

        TreeNode parent = nodeToDelete.getParent();
        if (parent != null) {
            parent.removeChild(nodeToDelete);
        }
        nodeMap.remove(nodeToDelete.getNodeId());

        return new Operation(
                Operation.Type.DELETE,
                String.valueOf(nodeToDelete.getContent()),
                Collections.singletonList(nodeToDelete.getNodeId()),
                System.currentTimeMillis(),
                clientId
        );
    }

    public boolean applyOperation(Operation operation) {
        if (operation.getType() == Operation.Type.INSERT) {
            if (operation.getContent().length() != 1) {
                return false;
            }

            String nodeId = operation.getNodeId();
            if (nodeId == null || nodeMap.containsKey(nodeId)) {
                return false;
            }

            Position position = pathListToPosition(operation.getPath());
            TreeNode newNode = new TreeNode(nodeId, position, operation.getContent().charAt(0));
            TreeNode parent = findParentNode(position);
            insertNodeInOrder(parent, newNode);
            nodeMap.put(nodeId, newNode);
            return true;
        } else if (operation.getType() == Operation.Type.DELETE) {
            String targetNodeId = operation.getPath().get(0);
            TreeNode nodeToDelete = nodeMap.get(targetNodeId);
            if (nodeToDelete != null) {
                TreeNode parent = nodeToDelete.getParent();
                if (parent != null) {
                    parent.removeChild(nodeToDelete);
                }
                nodeMap.remove(targetNodeId);
                return true;
            }
            return false;
        }
        return false;
    }

    private Position generatePositionForIndex(int index) {
        List<TreeNode> nodes = getAllNodesInOrder();
        if (index < 0 || index > nodes.size()) {
            throw new IndexOutOfBoundsException("Index out of bounds: " + index);
        }

        if (index == 0) {
            return Position.generatePositionBetween(null, nodes.isEmpty() ? null : nodes.get(0).getPosition(), clientId);
        } else if (index == nodes.size()) {
            return Position.generatePositionBetween(nodes.get(index - 1).getPosition(), null, clientId);
        } else {
            return Position.generatePositionBetween(
                    nodes.get(index - 1).getPosition(),
                    nodes.get(index).getPosition(),
                    clientId
            );
        }
    }

    private TreeNode findParentNode(Position position) {
        TreeNode current = root;
        TreeNode bestMatch = root;
        
        while (current != null) {
            boolean foundBetterMatch = false;
            for (TreeNode child : current.getChildren()) {
                if (child.getPosition().isAncestorOf(position)) {
                    current = child;
                    bestMatch = child;
                    foundBetterMatch = true;
                    break;
                }
            }
            if (!foundBetterMatch) {
                break;
            }
        }
        
        return bestMatch;
    }

    private TreeNode findNodeAtIndex(int index) {
        List<TreeNode> nodes = getAllNodesInOrder();
        if (index < 0 || index >= nodes.size()) {
            return null;
        }
        return nodes.get(index);
    }

    private List<TreeNode> getAllNodesInOrder() {
        List<TreeNode> result = new ArrayList<>();
        collectNodesInOrder(root, result);
        return result;
    }

    private void collectNodesInOrder(TreeNode node, List<TreeNode> result) {
        for (TreeNode child : node.getChildren()) {
            result.add(child);
            collectNodesInOrder(child, result);
        }
    }

    private void insertNodeInOrder(TreeNode parent, TreeNode newNode) {
        List<TreeNode> children = parent.getChildren();
        int idx = 0;
        for (; idx < children.size(); idx++) {
            if (newNode.getPosition().compareTo(children.get(idx).getPosition()) < 0) {
                break;
            }
        }
        children.add(idx, newNode);
        newNode.setParent(parent);
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

    public String getContent() {
        StringBuilder sb = new StringBuilder();
        for (TreeNode node : getAllNodesInOrder()) {
            if (node.getContent() != null) {
                sb.append(node.getContent());
            }
        }
        return sb.toString();
    }

    public List<String> getNodeIdsInOrder() {
        List<String> nodeIds = new ArrayList<>();
        for (TreeNode node : getAllNodesInOrder()) {
            nodeIds.add(node.getNodeId());
        }
        return nodeIds;
    }

    public void debugPrintStructure() {
        System.out.println("CRDT Structure:");
        System.out.println("Content: \"" + getContent() + "\"");
        System.out.println("Tree structure:");
        printNode(root, 0);
        System.out.println("---------------------");
    }

    private void printNode(TreeNode node, int depth) {
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            indent.append("  ");
        }
        System.out.println(indent.toString() + node.toString());
        for (TreeNode child : node.getChildren()) {
            printNode(child, depth + 1);
        }
    }
}
package org.example.crdt;

import java.util.ArrayList;
import java.util.List;

public class TreeNode {
    private final String nodeId;
    private Position position;
    private TreeNode parent;
    private final List<TreeNode> children;
    private Character content;

    public TreeNode(String nodeId, Position position, Character content) {
        this.nodeId = nodeId;
        this.position = position;
        this.content = content;
        this.children = new ArrayList<>();
    }

    public String getNodeId() {
        return nodeId;
    }

    public Position getPosition() {
        return position;
    }

    public void setPosition(Position position) {
        this.position = position;
    }

    public TreeNode getParent() {
        return parent;
    }

    public void setParent(TreeNode parent) {
        this.parent = parent;
    }

    public List<TreeNode> getChildren() {
        return children;
    }

    public Character getContent() {
        return content;
    }

    public void setContent(Character content) {
        this.content = content;
    }

    public void addChild(TreeNode child) {
        child.setParent(this);
        children.add(child);
    }

    public void removeChild(TreeNode child) {
        child.setParent(null);
        children.remove(child);
    }

    public void removeChildById(String nodeId) {
        children.removeIf(child -> child.getNodeId().equals(nodeId));
    }

    @Override
    public String toString() {
        return "TreeNode{" +
                "nodeId='" + nodeId + '\'' +
                ", position=" + position +
                ", content=" + content +
                ", children=" + children.size() +
                '}';
    }
} 
package org.example.crdt;

import java.util.ArrayList;
import java.util.List;

public class TreeNode {
    private final String nodeId;
    private final Position position;
    private final Character content;
    private TreeNode parent;
    private final List<TreeNode> children;

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

    public Character getContent() {
        return content;
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

    public void removeChild(TreeNode child) {
        children.remove(child);
        child.parent = null;
    }

    @Override
    public String toString() {
        return "TreeNode{nodeId='" + nodeId + "', position=" + position.getPath() + ", content=" + content + ", children=" + children.size() + "}";
    }
}
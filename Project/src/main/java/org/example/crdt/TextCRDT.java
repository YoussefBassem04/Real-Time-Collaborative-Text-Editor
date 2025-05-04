package org.example.crdt;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A CRDT implementation for a collaborative text editor
 */
public class TextCRDT implements Serializable {

    private static final long serialVersionUID = 1L;

    // A sorted map of positions to characters
    private final SortedMap<Position, Character> characters;

    // The client ID for this instance
    private final String clientId;

    // The position of the last inserted character
    private Position lastPosition;

    /**
     * Creates a new text CRDT with the specified client ID
     *
     * @param clientId The client ID for this instance
     */
    public TextCRDT(String clientId) {
        this.characters = new TreeMap<>();
        this.clientId = clientId;
        this.lastPosition = null;
    }

    /**
     * Inserts a character at the end of the document
     *
     * @param c The character to insert
     * @return The position where the character was inserted
     */
    public Position insertCharacter(char c) {
        Position newPosition = Position.generatePositionForAppend(lastPosition, clientId);
        characters.put(newPosition, c);
        lastPosition = newPosition;
        return newPosition;
    }

    /**
     * Inserts a character between two positions
     *
     * @param c The character to insert
     * @param before The position before the insertion point (can be null)
     * @param after The position after the insertion point (can be null)
     * @return The position where the character was inserted
     */
    public Position insertCharacterBetween(char c, Position before, Position after) {
        Position newPosition = Position.generatePositionBetween(before, after, clientId);
        characters.put(newPosition, c);
        return newPosition;
    }

    /**
     * Deletes a character at the specified position
     *
     * @param position The position of the character to delete
     * @return True if the character was deleted, false otherwise
     */
    public boolean deleteCharacter(Position position) {
        if (characters.containsKey(position)) {
            characters.remove(position);
            return true;
        }
        return false;
    }

    /**
     * Gets the current text from the CRDT
     *
     * @return The current text
     */
    public String getText() {
        StringBuilder sb = new StringBuilder();
        for (Character c : characters.values()) {
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Merges another CRDT into this one
     *
     * @param other The other CRDT to merge
     */
    public void merge(TextCRDT other) {
        other.characters.forEach((position, character) -> {
            if (!characters.containsKey(position)) {
                characters.put(position, character);
            }
        });
    }

    /**
     * Gets a map of positions to characters
     *
     * @return A map of positions to characters
     */
    public SortedMap<Position, Character> getCharacters() {
        return new TreeMap<>(characters);
    }

    /**
     * Inserts text at the end of the document
     *
     * @param text The text to insert
     * @return A list of positions where the characters were inserted
     */
    public List<Position> insertText(String text) {
        List<Position> positions = new ArrayList<>();
        for (char c : text.toCharArray()) {
            positions.add(insertCharacter(c));
        }
        return positions;
    }

    /**
     * Example usage showing the fix for consecutive characters
     */
    public static void main(String[] args) {
        TextCRDT crdt = new TextCRDT("client1");

        // Insert "meowsddddddzxczxcxxxxxxxxx"
        crdt.insertText("meows");

        // Insert many consecutive 'd' characters
        List<Position> dPositions = crdt.insertText("dddddddddddddd");

        // Insert "zxc"
        List<Position> zxcPositions = crdt.insertText("zxc");

        // Insert "zxc" again
        crdt.insertText("zxc");

        // Insert many consecutive 'x' characters

        crdt.insertText("x");
        crdt.insertText("x");
        crdt.insertText("x");
        crdt.insertText("x");
        crdt.insertText("x");
        crdt.insertText("x");
        crdt.insertText("x");
        crdt.insertText("x");
        crdt.insertText("x");
        crdt.insertText("x");
        crdt.insertText("x");
        crdt.insertText("x");
        crdt.insertText("d");
        crdt.insertText("d");
        crdt.insertText("d");
        crdt.insertText("d");
        crdt.insertText("d");
        crdt.insertText("d");
        crdt.insertText("d");
        crdt.insertText("x");
        crdt.insertText("x");
        crdt.insertText("x");
        crdt.insertText("x");

        // Print the text
        System.out.println("Final text: " + crdt.getText());

        // Debug: Print positions and characters
        System.out.println("\nPositions and characters:");
        crdt.getCharacters().forEach((position, character) -> {
            System.out.println(position + " -> " + character);
        });
    }
}
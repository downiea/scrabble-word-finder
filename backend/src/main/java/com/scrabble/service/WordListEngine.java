package com.scrabble.service;

import com.scrabble.model.Ruleset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.EnumMap;
import java.util.Map;

/**
 * Loads and queries word lists for each ruleset using a trie for fast prefix
 * and word lookups during move generation.
 *
 * Word list files should be placed at:
 *   src/main/resources/words/twl06.txt   (US)
 *   src/main/resources/words/collins.txt (UK)
 *
 * Each file should contain one uppercase word per line.
 * See src/main/resources/words/README.md for acquisition instructions.
 */
@Slf4j
@Service
public class WordListEngine {

    private final Map<Ruleset, TrieNode> tries = new EnumMap<>(Ruleset.class);

    @PostConstruct
    public void loadWordLists() {
        loadWordList(Ruleset.US, "words/twl06.txt");
        loadWordList(Ruleset.UK, "words/collins.txt");
    }

    private void loadWordList(Ruleset ruleset, String resourcePath) {
        TrieNode root = new TrieNode();
        int count = 0;
        try {
            ClassPathResource resource = new ClassPathResource(resourcePath);
            if (!resource.exists()) {
                log.warn("Word list not found at {}. {} ruleset will have no valid words. See resources/words/README.md", resourcePath, ruleset);
                tries.put(ruleset, root);
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String word = line.trim().toUpperCase();
                    if (!word.isEmpty() && word.matches("[A-Z]+")) {
                        insert(root, word);
                        count++;
                    }
                }
            }
            log.info("Loaded {} words for ruleset {}", count, ruleset);
        } catch (Exception e) {
            log.error("Failed to load word list for ruleset {}: {}", ruleset, e.getMessage());
        }
        tries.put(ruleset, root);
    }

    public boolean isValidWord(String word, Ruleset ruleset) {
        TrieNode node = findNode(tries.get(ruleset), word.toUpperCase());
        return node != null && node.isEndOfWord;
    }

    public boolean isValidPrefix(String prefix, Ruleset ruleset) {
        return findNode(tries.get(ruleset), prefix.toUpperCase()) != null;
    }

    private void insert(TrieNode root, String word) {
        TrieNode node = root;
        for (char c : word.toCharArray()) {
            node = node.children.computeIfAbsent(c, k -> new TrieNode());
        }
        node.isEndOfWord = true;
    }

    private TrieNode findNode(TrieNode root, String prefix) {
        if (root == null) return null;
        TrieNode node = root;
        for (char c : prefix.toCharArray()) {
            node = node.children.get(c);
            if (node == null) return null;
        }
        return node;
    }

    private static class TrieNode {
        final Map<Character, TrieNode> children = new java.util.HashMap<>();
        boolean isEndOfWord = false;
    }
}

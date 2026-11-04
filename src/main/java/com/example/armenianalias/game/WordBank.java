package com.example.armenianalias.game;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** Holds one word list per language and deals shuffled decks out of them. */
@Component
public class WordBank {

    public static final String ARMENIAN = "HY";
    public static final String ENGLISH = "EN";

    private final Map<String, List<WordCard>> banks;

    public WordBank() throws IOException {
        this.banks = Map.of(
                ARMENIAN, load("words.json"),
                ENGLISH, load("words-en.json"));
    }

    private static List<WordCard> load(String resource) throws IOException {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return new JsonMapper().readValue(in, new TypeReference<List<WordCard>>() {});
        }
    }

    /** Unknown or missing languages fall back to Armenian rather than dealing nothing. */
    public static String normalize(String language) {
        return ENGLISH.equalsIgnoreCase(language) ? ENGLISH : ARMENIAN;
    }

    private List<WordCard> bank(String language) {
        return banks.get(normalize(language));
    }

    public int size(String language) {
        return bank(language).size();
    }

    /**
     * @param levels which difficulty levels to include; empty means all
     */
    public Deque<WordCard> newDeck(String language, List<Integer> levels) {
        List<WordCard> pool = new ArrayList<>(bank(language));
        if (levels != null && !levels.isEmpty()) {
            pool.removeIf(card -> !levels.contains(card.level()));
        }
        if (pool.isEmpty()) {
            pool = new ArrayList<>(bank(language));
        }
        Collections.shuffle(pool);
        return new ArrayDeque<>(pool);
    }
}

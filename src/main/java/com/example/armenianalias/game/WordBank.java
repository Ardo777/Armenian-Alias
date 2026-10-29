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

/** Loads words.json once and deals shuffled decks out of it. */
@Component
public class WordBank {

    private final List<WordCard> all;

    public WordBank() throws IOException {
        try (InputStream in = new ClassPathResource("words.json").getInputStream()) {
            this.all = new JsonMapper().readValue(in, new TypeReference<List<WordCard>>() {});
        }
    }

    public int size() {
        return all.size();
    }

    /**
     * @param levels which difficulty levels to include; empty means all
     */
    public Deque<WordCard> newDeck(List<Integer> levels) {
        List<WordCard> pool = new ArrayList<>(all);
        if (levels != null && !levels.isEmpty()) {
            pool.removeIf(card -> !levels.contains(card.level()));
        }
        if (pool.isEmpty()) {
            pool = new ArrayList<>(all);
        }
        Collections.shuffle(pool);
        return new ArrayDeque<>(pool);
    }
}

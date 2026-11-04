package com.example.armenianalias.game;

import java.util.List;

/**
 * Բառ + արմատակից բառեր — a word together with the words that may not be spoken.
 *
 * @param translation rendering in the players' own language; null when the list has none
 *                    (the Armenian bank, where a translation would help nobody)
 */
public record WordCard(String word, int level, List<String> taboo, String translation) {

    public boolean hasTranslation() {
        return translation != null && !translation.isBlank();
    }
}

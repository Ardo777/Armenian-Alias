package com.example.armenianalias.game;

import java.util.List;

/**
 * Բառ + արմատակից բառեր — a word together with the same-root words that may not be spoken.
 */
public record WordCard(String word, int level, List<String> taboo) {
}

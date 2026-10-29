package com.example.armenianalias.game;

/** One word played during a turn, with the result the explainer tapped. */
public class TurnEntry {
    public static final String CORRECT = "CORRECT";
    public static final String SKIP = "SKIP";
    public static final String WRONG = "WRONG";

    private final String word;
    private String result;

    public TurnEntry(String word, String result) {
        this.word = word;
        this.result = result;
    }

    public String getWord() { return word; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    /** Points this entry is worth under the given settings. */
    public int points(GameSettings settings) {
        return switch (result) {
            case CORRECT -> 1;
            case WRONG -> settings.isPenaltyForWrong() ? -1 : 0;
            default -> 0;
        };
    }
}

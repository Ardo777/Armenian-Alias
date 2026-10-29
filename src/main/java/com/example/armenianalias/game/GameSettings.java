package com.example.armenianalias.game;

import java.util.ArrayList;
import java.util.List;

public class GameSettings {
    /** Seconds per turn. */
    private int roundSeconds = 60;
    /** Skips each team is granted at the start of every one of its turns. */
    private int skipsPerTurn = 3;
    /** Whether a red "not guessed" costs the team a point. */
    private boolean penaltyForWrong = true;
    /** SCORE = play until a team reaches targetScore; ROUNDS = play a fixed number of rounds. */
    private String winMode = "SCORE";
    private int targetScore = 30;
    private int totalRounds = 5;
    /** Difficulty levels in play: 1 easy, 2 medium, 3 hard. */
    private List<Integer> levels = new ArrayList<>(List.of(1, 2, 3));

    public int getRoundSeconds() { return roundSeconds; }
    public void setRoundSeconds(int roundSeconds) {
        this.roundSeconds = Math.clamp(roundSeconds, 15, 300);
    }

    public int getSkipsPerTurn() { return skipsPerTurn; }
    public void setSkipsPerTurn(int skipsPerTurn) {
        // 99 is the UI's "unlimited" — a turn can never burn through that many words.
        this.skipsPerTurn = Math.clamp(skipsPerTurn, 0, 99);
    }

    public boolean isPenaltyForWrong() { return penaltyForWrong; }
    public void setPenaltyForWrong(boolean penaltyForWrong) { this.penaltyForWrong = penaltyForWrong; }

    public String getWinMode() { return winMode; }
    public void setWinMode(String winMode) {
        this.winMode = "ROUNDS".equals(winMode) ? "ROUNDS" : "SCORE";
    }

    public int getTargetScore() { return targetScore; }
    public void setTargetScore(int targetScore) {
        this.targetScore = Math.clamp(targetScore, 5, 200);
    }

    public int getTotalRounds() { return totalRounds; }
    public void setTotalRounds(int totalRounds) {
        this.totalRounds = Math.clamp(totalRounds, 1, 30);
    }

    public List<Integer> getLevels() { return levels; }
    public void setLevels(List<Integer> levels) {
        List<Integer> cleaned = new ArrayList<>();
        if (levels != null) {
            for (Integer level : levels) {
                if (level != null && level >= 1 && level <= 3 && !cleaned.contains(level)) {
                    cleaned.add(level);
                }
            }
        }
        this.levels = cleaned.isEmpty() ? new ArrayList<>(List.of(1, 2, 3)) : cleaned;
    }
}

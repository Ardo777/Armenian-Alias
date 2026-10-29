package com.example.armenianalias.game;

import java.util.ArrayList;
import java.util.List;

public class Team {
    private final String id;
    private String name;
    private String emoji;
    private String colorFrom;
    private String colorTo;
    private int score;
    private final List<String> playerIds = new ArrayList<>();
    /** Rotates the explainer within the team from turn to turn. */
    private int explainerIndex;

    public Team(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmoji() { return emoji; }
    public void setEmoji(String emoji) { this.emoji = emoji; }

    public String getColorFrom() { return colorFrom; }
    public void setColorFrom(String colorFrom) { this.colorFrom = colorFrom; }

    public String getColorTo() { return colorTo; }
    public void setColorTo(String colorTo) { this.colorTo = colorTo; }

    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }
    public void addScore(int delta) { this.score += delta; }

    public List<String> getPlayerIds() { return playerIds; }

    public int getExplainerIndex() { return explainerIndex; }
    public void setExplainerIndex(int explainerIndex) { this.explainerIndex = explainerIndex; }
}

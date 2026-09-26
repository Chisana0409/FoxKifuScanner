package jp.chisana.foxkifuscanner;

import java.time.LocalDate;

public final class GameMetadata {
    public static final String DEFAULT_EVENT = "昇降級戦";
    public static final String DEFAULT_PLACE = "野狐囲碁";
    public static final String DEFAULT_RULE = "Chinese";
    public String blackName = "黒番不明";
    public String whiteName = "白番不明";
    public String blackRank = "";
    public String whiteRank = "";
    public String result = "";
    public String handicapText = "互先";
    public boolean handicapRecognized = false;
    public String date = LocalDate.now().toString();
    public String event = DEFAULT_EVENT;
    public String place = DEFAULT_PLACE;
    public String rule = DEFAULT_RULE;
    public String komi = "7.5";
    public int handicap = 0;
    public BoardState initialPosition = new BoardState(new byte[361]);

    public String blackDisplay() { return blackRank.isBlank() ? blackName : blackName + "(" + blackRank + ")"; }
    public String whiteDisplay() { return whiteRank.isBlank() ? whiteName : whiteName + "(" + whiteRank + ")"; }

    public void applyDefaultKomi() {
        komi = "互先".equals(handicapText) ? "7.5" : "0";
    }
}

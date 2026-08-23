package jp.chisana.foxkifuscanner;

public final class GameMetadata {
    public String blackName = "黒番不明";
    public String whiteName = "白番不明";
    public String blackRank = "";
    public String whiteRank = "";
    public String result = "";
    public String handicapText = "互先";
    public String place = "野狐囲碁";
    public int handicap = 0;
    public BoardState initialPosition = new BoardState(new byte[361]);

    public String blackDisplay() { return blackRank.isBlank() ? blackName : blackName + "(" + blackRank + ")"; }
    public String whiteDisplay() { return whiteRank.isBlank() ? whiteName : whiteName + "(" + whiteRank + ")"; }
}

package jp.chisana.foxkifuscanner;

import java.time.LocalDate;
import java.util.List;

public final class SgfWriter {
    private SgfWriter() {}

    public static String build(GameMetadata meta, List<Move> moves, LocalDate date) {
        StringBuilder out = new StringBuilder(2048);
        out.append("(;GM[1]FF[4]CA[UTF-8]AP[FoxKifuScanner:1.3.2-shs20]SZ[19]");
        out.append("DT[").append(date).append(']');
        out.append("PB[").append(escape(meta.blackName)).append(']');
        if (!meta.blackRank.isBlank()) out.append("BR[").append(escape(meta.blackRank)).append(']');
        out.append("PW[").append(escape(meta.whiteName)).append(']');
        if (!meta.whiteRank.isBlank()) out.append("WR[").append(escape(meta.whiteRank)).append(']');
        out.append("PC[").append(escape(meta.place.isBlank() ? "野狐囲碁" : meta.place)).append(']');
        out.append("RU[Chinese]");
        if (meta.handicap > 1) out.append("HA[").append(meta.handicap).append(']');
        appendSetup(out, "AB", meta.initialPosition, BoardState.BLACK);
        appendSetup(out, "AW", meta.initialPosition, BoardState.WHITE);
        if (!meta.result.isBlank()) out.append("RE[").append(escape(meta.result)).append(']');
        for (Move move : moves) {
            out.append(';').append(move.color() == BoardState.BLACK ? 'B' : 'W').append('[');
            if (!move.pass()) out.append((char) ('a' + move.x())).append((char) ('a' + move.y()));
            out.append(']');
        }
        return out.append(")\n").toString();
    }

    private static void appendSetup(StringBuilder out, String property, BoardState board, byte color) {
        boolean started = false;
        for (int y=0; y<19; y++) for (int x=0; x<19; x++) if (board.at(x,y) == color) {
            if (!started) { out.append(property); started = true; }
            out.append('[').append((char)('a'+x)).append((char)('a'+y)).append(']');
        }
    }

    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("]", "\\]").replace("\r", " ").replace("\n", " ");
    }
}

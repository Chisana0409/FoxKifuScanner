import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import jp.chisana.foxkifuscanner.*;

public final class CoreSelfTest {
    public static void main(String[] args) {
        GameMetadata m = new GameMetadata();
        m.blackName="chisana"; m.blackRank="10級"; m.whiteName="V532816174"; m.whiteRank="11級"; m.result="B+R";
        String normal = SgfWriter.build(m, List.of(new Move(BoardState.BLACK,3,3,false)), LocalDate.of(2026,8,23));
        require(normal.contains("PB[chisana]BR[10級]"), "black metadata");
        require(normal.contains("PW[V532816174]WR[11級]"), "white metadata");
        require(normal.contains("PC[野狐囲碁]"), "game place");
        require(normal.contains("RE[B+R]"), "result metadata");
        require(normal.contains(";B[dd]"), "SGF coordinate");

        byte[] setup = new byte[361]; setup[3*19+3]=BoardState.BLACK; setup[15*19+15]=BoardState.BLACK;
        m.handicap=2; m.initialPosition=new BoardState(setup);
        String handicap = SgfWriter.build(m, List.of(Move.pass(BoardState.WHITE)), LocalDate.of(2026,8,23));
        require(handicap.contains("HA[2]AB[dd][pp]"), "handicap setup");
        require(handicap.contains(";W[]"), "pass");

        byte[] before = new byte[361], after = new byte[361];
        before[0]=BoardState.WHITE; after[1]=BoardState.BLACK;
        MoveDetector.Result diff=MoveDetector.between(new BoardState(before),new BoardState(after),BoardState.BLACK);
        require(diff.legal() && diff.move().x()==1 && diff.move().y()==0, "capture difference");

        // Full synthetic replay: board recognition differences -> move list -> pass -> SGF.
        GameMetadata replayMeta = new GameMetadata();
        replayMeta.blackName = "Black"; replayMeta.whiteName = "White"; replayMeta.result = "B+R";
        BoardState state = new BoardState(new byte[361]);
        ArrayList<Move> replayMoves = new ArrayList<>();
        state = accept(state, board(1, 0, BoardState.BLACK), BoardState.BLACK, replayMoves);
        byte[] two = state.copyCells(); two[0] = BoardState.WHITE;
        state = accept(state, new BoardState(two), BoardState.WHITE, replayMoves);
        byte[] capture = state.copyCells(); capture[0] = BoardState.EMPTY; capture[19] = BoardState.BLACK;
        state = accept(state, new BoardState(capture), BoardState.BLACK, replayMoves);
        replayMoves.add(Move.pass(BoardState.WHITE));
        String replay = SgfWriter.build(replayMeta, replayMoves, LocalDate.of(2026,8,23));
        require(replay.contains(";B[ba];W[aa];B[ab];W[]"), "end-to-end replay SGF");
        require(replay.endsWith(")\n"), "complete SGF tree");
        System.out.println("CoreSelfTest: all checks passed");
    }

    private static BoardState board(int x, int y, byte color) {
        byte[] cells = new byte[361]; cells[y * 19 + x] = color; return new BoardState(cells);
    }

    private static BoardState accept(BoardState before, BoardState after, byte expected, List<Move> moves) {
        MoveDetector.Result result = MoveDetector.between(before, after, expected);
        require(result.legal(), "synthetic replay move");
        moves.add(result.move());
        return after;
    }
    private static void require(boolean ok, String name) { if (!ok) throw new AssertionError(name); }
}

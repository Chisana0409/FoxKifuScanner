package jp.chisana.foxkifuscanner;

import static org.junit.Assert.*;
import java.time.LocalDate;
import java.util.List;
import org.junit.Test;

public class CoreTest {
    @Test public void standardCoordinatesAndMetadata() {
        GameMetadata m = new GameMetadata();
        m.blackName = "chisana"; m.blackRank = "10級";
        m.whiteName = "V532816174"; m.whiteRank = "11級";
        m.result = "B+R";
        String s = SgfWriter.build(m, List.of(new Move(BoardState.BLACK, 3, 3, false)), LocalDate.of(2026, 8, 23));
        assertTrue(s.contains("PB[chisana]BR[10級]"));
        assertTrue(s.contains("PW[V532816174]WR[11級]"));
        assertTrue(s.contains("PC[野狐囲碁]"));
        assertTrue(s.contains("RE[B+R]"));
        assertTrue(s.contains(";B[dd]"));
    }

    @Test public void playerRankAndFoxResultAreParsedSeparately() {
        GameMetadata m = MetadataReader.readTextForTest(
                "chisana\n10級", "黒\n4と1/4子勝ち", "V532816174\n11級", true);
        assertEquals("chisana", m.blackName);
        assertEquals("10級", m.blackRank);
        assertEquals("V532816174", m.whiteName);
        assertEquals("11級", m.whiteRank);
        assertEquals("B+8.5", m.result);
        assertEquals("野狐囲碁", m.place);
    }

    @Test public void commonResultFormsAreParsed() {
        assertEquals("B+R", MetadataReader.parseResult("黒 中盤勝ち"));
        assertEquals("W+0.5", MetadataReader.parseResult("白 半目勝ち"));
        assertEquals("B+3.5", MetadataReader.parseResult("黒 3目半勝ち"));
        assertEquals("0", MetadataReader.parseResult("持碁"));
    }

    @Test public void captureIsLegalDifference() {
        byte[] a = new byte[361], b = new byte[361];
        a[0] = BoardState.WHITE;
        b[1] = BoardState.BLACK;
        MoveDetector.Result r = MoveDetector.between(new BoardState(a), new BoardState(b), BoardState.BLACK);
        assertTrue(r.legal());
        assertEquals(1, r.move().x());
    }

    @Test public void handicapSetupIsWritten() {
        GameMetadata m = new GameMetadata();
        m.handicap = 2;
        byte[] cells = new byte[361]; cells[3*19+3]=BoardState.BLACK; cells[15*19+15]=BoardState.BLACK;
        m.initialPosition = new BoardState(cells);
        String s = SgfWriter.build(m, List.of(Move.pass(BoardState.WHITE)), LocalDate.of(2026,8,23));
        assertTrue(s.contains("HA[2]"));
        assertTrue(s.contains("AB[dd][pp]"));
        assertTrue(s.contains(";W[]"));
    }
}

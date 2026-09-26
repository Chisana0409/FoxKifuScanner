package jp.chisana.foxkifuscanner;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
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

    @Test public void fractionalResultIsNotMistakenForHandicap() {
        GameMetadata m = MetadataReader.readTextForTest(
                "a532278851\n9級", "白勝ち\n28と1/4子", "chisana\n9級", true);
        assertEquals("互先", m.handicapText);
        assertEquals(0, m.handicap);
        assertFalse(m.handicapRecognized);
    }

    @Test public void titleHandicapLabelsAreParsed() {
        GameMetadata even = MetadataReader.readTextForTest(
                "昇降級戦 互先", "白勝ち\n28と1/4子", "", true);
        assertEquals("互先", even.handicapText);
        assertEquals(0, even.handicap);
        assertTrue(even.handicapRecognized);

        GameMetadata fixedBlack = MetadataReader.readTextForTest(
                "昇降級戦 定先", "白勝ち\n28と1/4子", "", true);
        assertEquals("定先", fixedBlack.handicapText);
        assertEquals(0, fixedBlack.handicap);
        assertTrue(fixedBlack.handicapRecognized);

        GameMetadata stones = MetadataReader.readTextForTest(
                "昇降級戦 二子", "白勝ち\n28と1/4子", "", true);
        assertEquals("2子", stones.handicapText);
        assertEquals(2, stones.handicap);
        assertTrue(stones.handicapRecognized);
    }

    @Test public void emptyInitialBoardPreservesFixedBlackHandicap() {
        GameMetadata m = MetadataReader.readTextForTest(
                "昇降級戦 定先", "", "", true);
        assertTrue(ReaderAccessibilityService.reconcileInitialPosition(
                new BoardState(new byte[361]), m));
        assertEquals("定先", m.handicapText);
        assertEquals(0, m.handicap);
        assertEquals("0", m.komi);
    }

    @Test public void emptyInitialBoardRejectsRecognizedStoneHandicap() {
        GameMetadata m = MetadataReader.readTextForTest(
                "昇降級戦 二子", "", "", true);
        assertFalse(ReaderAccessibilityService.reconcileInitialPosition(
                new BoardState(new byte[361]), m));
        assertEquals("2子", m.handicapText);
        assertEquals(2, m.handicap);
    }

    @Test public void commonResultFormsAreParsed() {
        assertEquals("B+R", MetadataReader.parseResult("黒 中盤勝ち"));
        assertEquals("B+R", MetadataReader.parseResult("黒\n中盤勝ち"));
        assertEquals("W+0.5", MetadataReader.parseResult("白 半目勝ち"));
        assertEquals("B+3.5", MetadataReader.parseResult("黒 3目半勝ち"));
        assertEquals("B+1.5", MetadataReader.parseResult("黒\n3/4子"));
        assertEquals("W+8.5", MetadataReader.parseResult("白 4と1/4子勝ち"));
        assertEquals("0", MetadataReader.parseResult("持碁"));
    }

    @Test public void nameScriptSelectionPrefersJapaneseWhenShortHanIsAmbiguous() {
        int japanese = NameScriptSelector.score("林", NameScriptSelector.OcrModel.JAPANESE,
                .82f, "ja");
        int chinese = NameScriptSelector.score("林", NameScriptSelector.OcrModel.CHINESE,
                .82f, "zh");
        assertTrue(japanese > chinese);
    }

    @Test public void nameScriptSelectionRecognizesClearChineseAndJapaneseEvidence() {
        int simplifiedChinese = NameScriptSelector.score("陈龙",
                NameScriptSelector.OcrModel.CHINESE, .80f, "zh");
        int japaneseLookAlike = NameScriptSelector.score("陳竜",
                NameScriptSelector.OcrModel.JAPANESE, .80f, "ja");
        assertTrue(simplifiedChinese > japaneseLookAlike);
        assertEquals(NameScriptSelector.ScriptKind.JAPANESE,
                NameScriptSelector.classify("ひかる碁"));
        assertEquals(NameScriptSelector.ScriptKind.LATIN,
                NameScriptSelector.classify("V532816174"));
    }

    @Test public void clearChineseConfidenceCanOverrideDefaultJapaneseTieBreak() {
        int japanese = NameScriptSelector.score("張偉", NameScriptSelector.OcrModel.JAPANESE,
                .60f, "ja");
        int chinese = NameScriptSelector.score("張偉", NameScriptSelector.OcrModel.CHINESE,
                .95f, "zh");
        assertTrue(chinese > japanese);
    }

    @Test public void fullChineseNameBeatsSingleKanaMisreadAcrossSameVisualSpan() {
        int japaneseMisread = NameScriptSelector.score("ち",
                NameScriptSelector.OcrModel.JAPANESE, 1.0f, "ja",
                1326, 320, 1326);
        int chineseName = NameScriptSelector.score("和平卫士",
                NameScriptSelector.OcrModel.CHINESE, .75f, "zh",
                1326, 320, 1326);
        assertTrue(chineseName > japaneseMisread);
    }

    @Test public void fullChineseLineBeatsSingleKanaElementFromThatLine() {
        int japaneseElement = NameScriptSelector.score("ち",
                NameScriptSelector.OcrModel.JAPANESE, 1.0f, "ja",
                76, 80, 320);
        int chineseName = NameScriptSelector.score("和平卫士",
                NameScriptSelector.OcrModel.CHINESE, .75f, "zh",
                320, 80, 320);
        assertTrue(chineseName > japaneseElement);
    }

    @Test public void genuineOneKanaNameStillKeepsJapanesePriority() {
        int japanese = NameScriptSelector.score("ち",
                NameScriptSelector.OcrModel.JAPANESE, .80f, "ja",
                76, 80, 76);
        int chinese = NameScriptSelector.score("池",
                NameScriptSelector.OcrModel.CHINESE, .80f, "zh",
                76, 80, 76);
        assertTrue(japanese > chinese);
    }

    @Test public void longLatinAccountNameIsNotDisplacedByChineseLookAlike() {
        int latin = NameScriptSelector.score("V532816174",
                NameScriptSelector.OcrModel.JAPANESE, .80f, "en",
                464, 80, 464);
        int chineseLookAlike = NameScriptSelector.score("和平卫士",
                NameScriptSelector.OcrModel.CHINESE, .80f, "zh",
                320, 80, 464);
        assertTrue(latin > chineseLookAlike);
    }

    @Test public void ocrPipelineKeepsTheStableListMetadataAbi() throws Exception {
        assertEquals(List.class, HeaderTextRecognizer.class.getDeclaredMethod(
                "recognize", Bitmap.class, BoardAnalyzer.Region.class).getReturnType());
        assertNotNull(MetadataReader.class.getDeclaredMethod("read", Bitmap.class,
                BoardAnalyzer.Region.class, List.class, List.class));
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

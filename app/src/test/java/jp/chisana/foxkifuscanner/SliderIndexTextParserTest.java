package jp.chisana.foxkifuscanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class SliderIndexTextParserTest {
    @Test public void parsesPlainAsciiPair() {
        assertSnapshot(12, 345, "12/345");
    }

    @Test public void parsesWhitespaceAndFullWidthSlash() {
        assertSnapshot(12, 345, "  12 / 345  ");
        assertSnapshot(12, 345, "12／345");
        assertSnapshot(12, 345, "\u00a012\u00a0／\u00a0345\u00a0");
    }

    @Test public void parsesJapaneseAndChineseMoveMarkers() {
        assertSnapshot(12, 345, "12手/345手");
        assertSnapshot(12, 345, "12 手 ／ 345 手");
        assertSnapshot(12, 345, "第12手/共345手");
        assertSnapshot(12, 345, "第 12 手 ／ 共 345 手");
    }

    @Test public void acceptsBoundaryValues() {
        assertSnapshot(0, 0, "0/0");
        assertSnapshot(0, 1000, "0/1000");
        assertSnapshot(1000, 1000, "1000/1000");
    }

    @Test public void rejectsNullEmptyAndSingleNumbers() {
        assertNull(SliderIndexTextParser.parse(null));
        assertRejected("", "   ", "12", "345手", "第12手");
    }

    @Test public void rejectsOutOfRangeOrReversedPairs() {
        assertRejected("0/1001", "1001/1001", "346/345", "第9999手/共9999手");
    }

    @Test public void rejectsClockDatesAndIpAddresses() {
        assertRejected(
                "12:34", "12:34:56", "2026/09/05", "192.168.1.12",
                "12:34 12/345", "192.168.1.12 12/345");
    }

    @Test public void rejectsRanksResultsAndUnrelatedLabels() {
        assertRejected(
                "3段/4段", "九段 12/345", "黒12目半勝ち", "白中押し勝ち",
                "手数 12/345", "move 12/345", "12/345 moves");
    }

    @Test public void rejectsAmbiguousMultiplePairs() {
        assertRejected(
                "12/345 13/345", "12/345,13/345", "12手/345手 第12手/共345手");
    }

    @Test public void rejectsMalformedAndPotentiallyConfusingNumbers() {
        assertRejected(
                "-1/345", "+12/345", "12.0/345", "12/345.0", "12//345",
                "12手/345", "12/345手", "第12/共345", "第12手/345手",
                "012/345", "12/0345", "１２／３４５");
    }

    private static void assertSnapshot(int current, int total, String text) {
        assertEquals(new SliderIndexTextParser.Snapshot(current, total),
                SliderIndexTextParser.parse(text));
    }

    private static void assertRejected(String... values) {
        for (String value : values) {
            assertNull("expected rejection: " + value, SliderIndexTextParser.parse(value));
        }
    }
}

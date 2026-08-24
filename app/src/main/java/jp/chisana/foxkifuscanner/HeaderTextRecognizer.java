package jp.chisana.foxkifuscanner;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Reads only the game-information header. Board and advertisement text are excluded. */
public final class HeaderTextRecognizer {
    private static final long PASS_TIMEOUT_SECONDS = 12;
    private static final Pattern RANK_LIKE = Pattern.compile(
            "[0-9０-９一二三四五六七八九十IOIlｌ｜丨]+[級级段]");
    private static final Pattern NAME_TOKEN = Pattern.compile("[\\p{L}\\p{N}_.-]{1,32}");
    private static final String REJECTED_NAME_WORDS =
            "昇降級戦|互先|定先|先手|譲る|中盤|投了|時間切れ|不戦|勝ち|黒番|白番|"
                    + "絶芸|復盤|ポイント|形勢|研究|アイコン|キャンセル|確認|棋譜";

    private HeaderTextRecognizer() {}

    /**
     * Returns the same List-based ABI used by v1.2.0. Multilingual candidate selection
     * remains internal so a stale caller/callee pair cannot introduce a new missing method.
     */
    public static List<MetadataReader.UiText> recognize(
            Bitmap screen, BoardAnalyzer.Region board) throws Exception {
        int width = screen.getWidth();
        int top = board.top();
        int playerTop = Math.max(0, top - Math.round(width * .155f));
        int playerBottom = Math.min(screen.getHeight(), top - Math.round(width * .010f));

        ArrayList<Crop> passes = new ArrayList<>();
        passes.add(new Crop(0, Math.max(0, top - Math.round(width * .275f)),
                width, top, 2, PlayerSide.NONE, true));
        passes.add(new Crop(Math.round(width * .145f), playerTop,
                Math.round(width * .475f), playerBottom, 3, PlayerSide.LEFT, false));
        passes.add(new Crop(Math.round(width * .375f), playerTop,
                Math.round(width * .625f), playerBottom, 3, PlayerSide.NONE, true));
        passes.add(new Crop(Math.round(width * .525f), playerTop,
                Math.round(width * .855f), playerBottom, 3, PlayerSide.RIGHT, false));

        Map<String, MetadataReader.UiText> found = new LinkedHashMap<>();
        Map<String, NameCandidate> names = new LinkedHashMap<>();
        TextRecognizer japanese = TextRecognition.getClient(
                new JapaneseTextRecognizerOptions.Builder().build());
        try {
            for (Crop crop : passes) {
                recognizePass(screen, crop, japanese, NameScriptSelector.OcrModel.JAPANESE,
                        found, names);
            }
        } finally {
            japanese.close();
        }

        TextRecognizer chinese = null;
        try {
            chinese = TextRecognition.getClient(
                    new ChineseTextRecognizerOptions.Builder().build());
            for (Crop crop : passes) {
                if (crop.side == PlayerSide.NONE) continue;
                recognizePass(screen, crop, chinese, NameScriptSelector.OcrModel.CHINESE,
                        found, names);
            }
        } catch (Throwable ignored) {
            // The Japanese/Latin pass remains a complete fallback.
        } finally {
            if (chinese != null) chinese.close();
        }

        // Remove the raw name rows from the full Japanese pass and add only the selected
        // multilingual result. Rank, hand and result rows remain untouched.
        removeNameBands(found, width, top);
        addSelectedName(found, selectName(names.values(), PlayerSide.LEFT, width, top), "left");
        addSelectedName(found, selectName(names.values(), PlayerSide.RIGHT, width, top), "right");
        return List.copyOf(found.values());
    }

    private static void recognizePass(Bitmap screen, Crop crop, TextRecognizer recognizer,
                                      NameScriptSelector.OcrModel model,
                                      Map<String, MetadataReader.UiText> found,
                                      Map<String, NameCandidate> names) throws Exception {
        if (crop.right <= crop.left || crop.bottom <= crop.top) return;
        Bitmap source = Bitmap.createBitmap(screen, crop.left, crop.top,
                crop.right - crop.left, crop.bottom - crop.top);
        Bitmap scaled = source;
        if (crop.scale > 1) {
            scaled = Bitmap.createScaledBitmap(source,
                    source.getWidth() * crop.scale, source.getHeight() * crop.scale, true);
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Text> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        boolean completed = false;
        try {
            recognizer.process(InputImage.fromBitmap(scaled, 0))
                    .addOnSuccessListener(value -> {
                        result.set(value);
                        latch.countDown();
                    })
                    .addOnFailureListener(error -> {
                        failure.set(error);
                        latch.countDown();
                    });
            completed = latch.await(PASS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) throw new IllegalStateException("対局情報OCRが時間内に完了しません");
            if (failure.get() != null) throw failure.get();
            Text text = result.get();
            if (text == null) return;
            for (Text.TextBlock block : text.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    if (crop.addHeaderText) {
                        add(found, line.getText(), line.getBoundingBox(), crop);
                    }
                    if (crop.side != PlayerSide.NONE) {
                        addName(names, line.getText(), line.getBoundingBox(), crop, model,
                                line.getRecognizedLanguage(), line.getConfidence());
                    }
                    for (Text.Element element : line.getElements()) {
                        if (crop.addHeaderText) {
                            add(found, element.getText(), element.getBoundingBox(), crop);
                        }
                        if (crop.side != PlayerSide.NONE) {
                            addName(names, element.getText(), element.getBoundingBox(), crop, model,
                                    element.getRecognizedLanguage(), element.getConfidence());
                        }
                    }
                }
            }
        } finally {
            if (completed) {
                if (scaled != source) scaled.recycle();
                source.recycle();
            }
        }
    }

    private static NameCandidate selectName(Iterable<NameCandidate> names, PlayerSide side,
                                            int width, int boardTop) {
        int expectedNameY = boardTop - Math.round(width * .114f);
        int bandTop = boardTop - Math.round(width * .160f);
        int bandBottom = boardTop - Math.round(width * .073f);
        int widest = 0;
        for (NameCandidate item : names) {
            if (item.side != side || !insideNameBand(item.bounds, side, width,
                    bandTop, bandBottom) || nameCandidates(item.text).isEmpty()) continue;
            widest = Math.max(widest, item.bounds.width());
        }

        NameCandidate best = null;
        int bestScore = Integer.MIN_VALUE;
        for (NameCandidate item : names) {
            if (item.side != side || !insideNameBand(item.bounds, side, width,
                    bandTop, bandBottom)) continue;
            for (String candidate : nameCandidates(item.text)) {
                int score = 2300 - Math.abs(item.bounds.centerY() - expectedNameY) * 3;
                score += NameScriptSelector.score(candidate, item.model, item.confidence,
                        item.recognizedLanguage, item.bounds.width(), item.bounds.height(), widest);
                if (item.bounds.width() < width * .40) score += 25;
                if (score > bestScore) {
                    bestScore = score;
                    best = new NameCandidate(candidate, item.bounds, item.model,
                            item.recognizedLanguage, item.confidence, item.side);
                }
            }
        }
        return best;
    }

    private static boolean insideNameBand(Rect bounds, PlayerSide side, int width,
                                          int bandTop, int bandBottom) {
        int cx = bounds.centerX(), cy = bounds.centerY();
        boolean onSide = side == PlayerSide.LEFT
                ? cx >= width * .075 && cx < width * .485
                : cx > width * .515 && cx <= width * .925;
        return onSide && cy >= bandTop && cy <= bandBottom;
    }

    private static void removeNameBands(Map<String, MetadataReader.UiText> found,
                                        int width, int boardTop) {
        int bandTop = boardTop - Math.round(width * .160f);
        int bandBottom = boardTop - Math.round(width * .073f);
        found.entrySet().removeIf(entry -> {
            Rect bounds = entry.getValue().bounds();
            return insideNameBand(bounds, PlayerSide.LEFT, width, bandTop, bandBottom)
                    || insideNameBand(bounds, PlayerSide.RIGHT, width, bandTop, bandBottom);
        });
    }

    private static void addSelectedName(Map<String, MetadataReader.UiText> found,
                                        NameCandidate selected, String side) {
        if (selected == null) return;
        found.put("selected-name-" + side,
                new MetadataReader.UiText(selected.text, new Rect(selected.bounds)));
    }

    private static List<String> nameCandidates(String raw) {
        String value = RANK_LIKE.matcher(raw == null ? "" : raw).replaceAll(" ")
                .replace('：', ' ').replace(':', ' ').replace('｜', ' ').replace('|', ' ')
                .replaceAll("[（）()【】\\[\\],，、。]", " ").trim();
        ArrayList<String> result = new ArrayList<>();
        for (String part : value.split("\\s+")) addNameCandidate(result, part);
        addNameCandidate(result, value.replaceAll("\\s+", ""));
        return result;
    }

    private static void addNameCandidate(List<String> result, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!NAME_TOKEN.matcher(value).matches() || !value.matches(".*\\p{L}.*")) return;
        if (value.matches(".*(" + REJECTED_NAME_WORDS + ").*")) return;
        if (value.equals("黒") || value.equals("白") || value.equals("級")
                || value.equals("级") || value.equals("段")) return;
        if (!result.contains(value)) result.add(value);
    }

    private static void add(Map<String, MetadataReader.UiText> found, String value,
                            Rect local, Crop crop) {
        if (value == null || value.isBlank() || local == null || local.isEmpty()) return;
        Rect screen = toScreen(local, crop);
        String text = value.trim();
        String key = text + '|' + Math.round(screen.centerX() / 8f) + '|'
                + Math.round(screen.centerY() / 8f);
        found.putIfAbsent(key, new MetadataReader.UiText(text, screen));
    }

    private static void addName(Map<String, NameCandidate> found, String value,
                                Rect local, Crop crop, NameScriptSelector.OcrModel model,
                                String language, float confidence) {
        if (value == null || value.isBlank() || local == null || local.isEmpty()) return;
        Rect screen = toScreen(local, crop);
        String text = value.trim();
        String key = model + "|" + crop.side + "|" + text + '|'
                + Math.round(screen.centerX() / 8f) + '|'
                + Math.round(screen.centerY() / 8f);
        float normalized = confidence >= 0f && confidence <= 1f ? confidence : 0f;
        NameCandidate existing = found.get(key);
        if (existing == null || normalized > existing.confidence) {
            found.put(key, new NameCandidate(text, screen, model,
                    language == null ? "" : language, normalized, crop.side));
        }
    }

    private static Rect toScreen(Rect local, Crop crop) {
        return new Rect(
                crop.left + Math.round(local.left / (float) crop.scale),
                crop.top + Math.round(local.top / (float) crop.scale),
                crop.left + Math.round(local.right / (float) crop.scale),
                crop.top + Math.round(local.bottom / (float) crop.scale));
    }

    private enum PlayerSide { NONE, LEFT, RIGHT }

    private record Crop(int left, int top, int right, int bottom, int scale,
                        PlayerSide side, boolean addHeaderText) {}

    private record NameCandidate(String text, Rect bounds,
                                 NameScriptSelector.OcrModel model,
                                 String recognizedLanguage, float confidence,
                                 PlayerSide side) {}
}

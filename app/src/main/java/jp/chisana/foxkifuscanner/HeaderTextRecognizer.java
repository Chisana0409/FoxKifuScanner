package jp.chisana.foxkifuscanner;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Reads only the game-information header. Board and advertisement text are excluded. */
public final class HeaderTextRecognizer {
    private static final long PASS_TIMEOUT_SECONDS = 12;

    private HeaderTextRecognizer() {}

    public static List<MetadataReader.UiText> recognize(
            Bitmap screen, BoardAnalyzer.Region board) throws Exception {
        int width = screen.getWidth();
        int top = board.top();
        int playerTop = Math.max(0, top - Math.round(width * .155f));
        int playerBottom = Math.min(screen.getHeight(), top - Math.round(width * .010f));

        ArrayList<Crop> passes = new ArrayList<>();
        // The full header provides the hand and result. Narrow player crops prevent the
        // title, board and avatar artwork from being mistaken for a player name.
        passes.add(new Crop(0, Math.max(0, top - Math.round(width * .275f)), width, top, 2));
        passes.add(new Crop(Math.round(width * .145f), playerTop,
                Math.round(width * .475f), playerBottom, 3));
        passes.add(new Crop(Math.round(width * .375f), playerTop,
                Math.round(width * .625f), playerBottom, 3));
        passes.add(new Crop(Math.round(width * .525f), playerTop,
                Math.round(width * .855f), playerBottom, 3));

        Map<String, MetadataReader.UiText> found = new LinkedHashMap<>();
        TextRecognizer recognizer = TextRecognition.getClient(
                new JapaneseTextRecognizerOptions.Builder().build());
        try {
            for (Crop crop : passes) recognizePass(screen, crop, recognizer, found);
        } finally {
            recognizer.close();
        }
        return List.copyOf(found.values());
    }

    private static void recognizePass(Bitmap screen, Crop crop, TextRecognizer recognizer,
                                      Map<String, MetadataReader.UiText> found) throws Exception {
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
            if (!completed) {
                throw new IllegalStateException("対局情報OCRが時間内に完了しません");
            }
            if (failure.get() != null) throw failure.get();
            Text text = result.get();
            if (text == null) return;
            for (Text.TextBlock block : text.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    add(found, line.getText(), line.getBoundingBox(), crop);
                    for (Text.Element element : line.getElements()) {
                        add(found, element.getText(), element.getBoundingBox(), crop);
                    }
                }
            }
        } finally {
            // A timed-out ML task can still be reading its InputImage. Avoid recycling that
            // backing bitmap until the task has signalled completion.
            if (completed) {
                if (scaled != source) scaled.recycle();
                source.recycle();
            }
        }
    }

    private static void add(Map<String, MetadataReader.UiText> found, String value,
                            Rect local, Crop crop) {
        if (value == null || value.isBlank() || local == null || local.isEmpty()) return;
        Rect screen = new Rect(
                crop.left + Math.round(local.left / (float) crop.scale),
                crop.top + Math.round(local.top / (float) crop.scale),
                crop.left + Math.round(local.right / (float) crop.scale),
                crop.top + Math.round(local.bottom / (float) crop.scale));
        String text = value.trim();
        String key = text + '|' + Math.round(screen.centerX() / 8f) + '|'
                + Math.round(screen.centerY() / 8f);
        found.putIfAbsent(key, new MetadataReader.UiText(text, screen));
    }

    private record Crop(int left, int top, int right, int bottom, int scale) {}
}

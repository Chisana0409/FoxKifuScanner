package jp.chisana.foxkifuscanner;

import android.graphics.Bitmap;

import java.util.List;

/** Host-side regression checks for multilingual name selection and the OCR ABI. */
public final class OcrContractSelfTest {
    private OcrContractSelfTest() {}

    public static void main(String[] args) throws Exception {
        require(score("林", NameScriptSelector.OcrModel.JAPANESE, .82f, "ja")
                        > score("林", NameScriptSelector.OcrModel.CHINESE, .82f, "zh"),
                "short ambiguous Han prefers Japanese");

        require(score("陈龙", NameScriptSelector.OcrModel.CHINESE, .80f, "zh")
                        > score("陳竜", NameScriptSelector.OcrModel.JAPANESE, .80f, "ja"),
                "simplified Chinese evidence");

        require(NameScriptSelector.score("和平卫士", NameScriptSelector.OcrModel.CHINESE,
                        .75f, "zh", 1326, 320, 1326)
                        > NameScriptSelector.score("ち", NameScriptSelector.OcrModel.JAPANESE,
                        1.0f, "ja", 1326, 320, 1326),
                "full Chinese name beats one-kana span mismatch");

        require(NameScriptSelector.score("和平卫士", NameScriptSelector.OcrModel.CHINESE,
                        .75f, "zh", 320, 80, 320)
                        > NameScriptSelector.score("ち", NameScriptSelector.OcrModel.JAPANESE,
                        1.0f, "ja", 76, 80, 320),
                "full line beats one-element kana misread");

        require(NameScriptSelector.score("ち", NameScriptSelector.OcrModel.JAPANESE,
                        .80f, "ja", 76, 80, 76)
                        > NameScriptSelector.score("池", NameScriptSelector.OcrModel.CHINESE,
                        .80f, "zh", 76, 80, 76),
                "genuine short Japanese name retains priority");

        require(List.class.equals(HeaderTextRecognizer.class.getDeclaredMethod(
                        "recognize", Bitmap.class, BoardAnalyzer.Region.class).getReturnType()),
                "HeaderTextRecognizer keeps List return ABI");
        require(MetadataReader.class.getDeclaredMethod("read", Bitmap.class,
                        BoardAnalyzer.Region.class, List.class, List.class) != null,
                "MetadataReader keeps stable List overload");

        System.out.println("OcrContractSelfTest: all checks passed");
    }

    private static int score(String text, NameScriptSelector.OcrModel model,
                             float confidence, String language) {
        return NameScriptSelector.score(text, model, confidence, language);
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}

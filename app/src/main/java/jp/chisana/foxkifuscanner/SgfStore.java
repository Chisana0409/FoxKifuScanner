package jp.chisana.foxkifuscanner;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public final class SgfStore {
    private SgfStore() {}

    public static Uri save(Context context, GameMetadata meta, String sgf, LocalDate date) throws Exception {
        String filename = safe(meta.blackName) + "_vs_" + safe(meta.whiteName) + "_"
                + date.format(DateTimeFormatter.BASIC_ISO_DATE) + ".sgf";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/x-go-sgf");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("Downloadsへの保存先を作成できません");
        try {
            try (OutputStream out = resolver.openOutputStream(uri, "w")) {
                if (out == null) throw new IllegalStateException("保存ファイルを開けません");
                out.write(sgf.getBytes(StandardCharsets.UTF_8));
            }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return uri;
        } catch (Exception e) {
            resolver.delete(uri, null, null);
            throw e;
        }
    }

    private static String safe(String name) {
        String v = name == null ? "不明" : name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return v.isBlank() ? "不明" : v;
    }
}

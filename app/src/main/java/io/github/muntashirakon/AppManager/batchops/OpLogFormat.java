// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.batchops;

import android.content.Context;

import androidx.annotation.NonNull;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.github.muntashirakon.AppManager.fonts.ColorPrefs;

/**
 * Fork (白い熊, +116): how one {@link OpLog.Entry} is rendered — the timestamp, the indent
 * ladder, the marker glyph and the colour. One place, because the log is drawn twice: on screen
 * by {@link OpLogAdapter} and as plain text by {@link OpLog#asText()} for Copy and Save. Two
 * renderers would drift, and the saved log would stop matching the one that was read.
 *
 * <p>The ladder is drawn rather than computed: a live log cannot know whether a line is the last
 * of its branch, so there is no {@code └}. Each level contributes {@code │ } and the line itself
 * starts with {@code ├ } — which is exactly true at the moment it is written, and stays true.
 */
public final class OpLogFormat {
    private OpLogFormat() {
    }

    private static final ThreadLocal<SimpleDateFormat> TIME = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
        }
    };

    @NonNull
    public static String timestamp(long millis) {
        //noinspection ConstantConditions
        return TIME.get().format(new Date(millis));
    }

    /** The indent ladder for a line at {@code depth}. Empty at depth 0. */
    @NonNull
    public static String guides(int depth) {
        if (depth <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(depth * 2);
        for (int i = 1; i < depth; ++i) {
            sb.append("│ ");
        }
        sb.append("├ ");
        return sb.toString();
    }

    /** The glyph that carries the outcome, where there is one. */
    @NonNull
    public static String marker(int kind, boolean continued) {
        switch (kind) {
            case OpLog.KIND_OK:
                return "✓ ";
            case OpLog.KIND_FAIL:
                return "✗ ";
            case OpLog.KIND_SKIP:
                return "– ";
            case OpLog.KIND_WARN:
                return "! ";
            case OpLog.KIND_APP:
                return continued ? "↳ " : "";
            default:
                return "";
        }
    }

    /** The colour a kind is drawn in. Every one is user-settable, like the rest of the fork. */
    public static int color(@NonNull Context context, int kind, boolean continued) {
        switch (kind) {
            case OpLog.KIND_BATCH:
            case OpLog.KIND_SUMMARY:
                return ColorPrefs.getColor(context, ColorPrefs.OPLOG_BATCH);
            case OpLog.KIND_APP:
                return ColorPrefs.getColor(context,
                        continued ? ColorPrefs.OPLOG_GUIDE : ColorPrefs.OPLOG_APP);
            case OpLog.KIND_STAGE:
                return ColorPrefs.getColor(context, ColorPrefs.OPLOG_STAGE);
            case OpLog.KIND_OK:
                return ColorPrefs.getColor(context, ColorPrefs.OPLOG_OK);
            case OpLog.KIND_FAIL:
                return ColorPrefs.getColor(context, ColorPrefs.OPLOG_FAIL);
            case OpLog.KIND_SKIP:
                return ColorPrefs.getColor(context, ColorPrefs.OPLOG_SKIP);
            case OpLog.KIND_WARN:
                return ColorPrefs.getColor(context, ColorPrefs.OPLOG_WARN);
            case OpLog.KIND_ITEM:
            default:
                return ColorPrefs.getColor(context, ColorPrefs.OPLOG_ITEM);
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.appdata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Fork: the small JSON header a sister app returns from {@code describe}.
 * <p>
 * It is deliberately <b>not</b> inside the archive. 応用管理 must draw a list row before any
 * export exists, and at restore must judge compatibility <b>before</b> streaming tens of
 * megabytes into an app that would reject them — neither is possible if the header is buried
 * inside an encrypted archive.
 */
public class AppDataHeader {
    public final String appId;
    public final long versionCode;
    public final String versionName;
    /** The format this app writes. Per-app, never a contract constant. */
    public final int format;
    /** The oldest format this app can read. The restore gate compares against this. */
    public final int minFormatReadable;
    /**
     * What the app claims about needing to be launched once before an import.
     * <p>
     * Recorded so the stored header stays faithful to what the app said, but deliberately
     * <b>not acted on</b> (白い熊, +107): an app is already running when it imports, because the
     * provider call starts its process, so this could only mean a person had opened it and its
     * first-run initialisation had happened — which is precisely what install → do-not-launch →
     * import exists to avoid.
     */
    public final boolean requiresLaunchFirst;
    /**
     * Runtime permissions the app needs held before an import will succeed. A freshly installed,
     * never-launched app holds none, so an import writing through a permission-guarded provider
     * fails <b>after</b> the archive has been streamed. Asked for before streaming, never after,
     * and never inferred from the app's category.
     */
    public final List<String> requiresPermissions;
    /** Short human strings, rendered verbatim, so each app describes itself. */
    public final List<String> contains;

    private AppDataHeader(String appId, long versionCode, String versionName, int format,
                          int minFormatReadable, boolean requiresLaunchFirst,
                          List<String> requiresPermissions, List<String> contains) {
        this.appId = appId;
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.format = format;
        this.minFormatReadable = minFormatReadable;
        this.requiresLaunchFirst = requiresLaunchFirst;
        this.requiresPermissions = requiresPermissions;
        this.contains = contains;
    }

    /**
     * Parse a header. Returns {@code null} rather than throwing: a malformed header from another
     * app is a refusal to proceed, not a crash in ours.
     */
    @Nullable
    public static AppDataHeader parse(@Nullable String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(json);
            String appId = o.optString("app_id", null);
            if (appId == null || appId.isEmpty()) {
                return null;
            }
            int format = o.optInt("format", -1);
            int minFormat = o.optInt("min_format_readable", -1);
            if (format < 0 || minFormat < 0) {
                return null;
            }
            return new AppDataHeader(appId,
                    o.optLong("version_code", 0L),
                    o.optString("version_name", ""),
                    format,
                    minFormat,
                    o.optBoolean("requires_launch_first", false),
                    stringList(o.optJSONArray("requires_permissions")),
                    stringList(o.optJSONArray("contains")));
        } catch (Throwable th) {
            return null;
        }
    }

    @NonNull
    private static List<String> stringList(@Nullable JSONArray array) {
        List<String> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (int i = 0; i < array.length(); ++i) {
            String s = array.optString(i, null);
            if (s != null && !s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * Whether an archive written by this header's app can be read back by {@code installed}.
     * <p>
     * Version skew has a direction: old data into a newer app is normally fine because an app
     * migrates its own storage; newer data into an older app is not. This is what lets a restore
     * be refused at discovery time rather than halfway through.
     */
    public boolean isRestorableInto(@NonNull AppDataContract.Support installed) {
        return format >= installed.minFormat;
    }

    @NonNull
    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("app_id", appId);
            o.put("version_code", versionCode);
            o.put("version_name", versionName);
            o.put("format", format);
            o.put("min_format_readable", minFormatReadable);
            o.put("requires_launch_first", requiresLaunchFirst);
            o.put("requires_permissions", new JSONArray(requiresPermissions));
            o.put("contains", new JSONArray(contains));
        } catch (Throwable ignore) {
        }
        return o.toString();
    }
}

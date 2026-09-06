// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.trackers;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.muntashirakon.AppManager.StaticDataset;
import io.github.muntashirakon.AppManager.fm.ContentType2;
import io.github.muntashirakon.AppManager.rules.RuleType;
import io.github.muntashirakon.AppManager.utils.PackageUtils;
import io.github.muntashirakon.algo.AhoCorasick;
import io.github.muntashirakon.io.Paths;
import io.github.muntashirakon.io.fs.DexFileSystem;
import io.github.muntashirakon.io.fs.VirtualFileSystem;

/**
 * Fork (白い熊, +146): what tracks you inside one app, and what it is able to do there.
 *
 * <p>Two depths, and the difference is the point.
 *
 * <ol>
 *   <li><b>The manifest scan</b> runs the app's own component names through the same
 *       Aho–Corasick index {@link io.github.muntashirakon.AppManager.rules.compontents.ComponentUtils}
 *       already uses for {@code App.trackerCount}. It costs nothing measurable, so it can run for
 *       every row of a list — and it also yields the tracker <em>names</em>, because
 *       {@link AhoCorasick#search} returns the matched signature indices and
 *       {@code tracker_names} is parallel to {@code tracker_signatures}. It is a <b>floor</b>:
 *       an SDK that ships no component is invisible to it.</li>
 *   <li><b>The deep scan</b> mounts the base APK as a dex filesystem and walks every class name,
 *       the way the Scanner screen does. It is the honest number, and it takes seconds and real
 *       I/O per app — so it is offered per app, on demand, and its result is cached against the
 *       app's {@code lastUpdateTime} so an update re-scans by itself.</li>
 * </ol>
 *
 * <p>Results are grouped by tracker <b>name</b>, not by signature: several signatures share one
 * name in the dataset (AD(X) has two), and a pill per signature would say the same word twice.
 */
public final class TrackerScan {
    public static final String PREF_FILE = "shiroikuma_trackers";
    /** The dataset's own mark for a second-degree tracker. */
    private static final char SECOND_DEGREE = '²';

    private TrackerScan() {
    }

    /**
     * Every tracker with a component in this app, worst rung first. Cheap enough for a list row.
     */
    @WorkerThread
    @NonNull
    public static List<TrackerHit> fromManifest(@Nullable PackageInfo packageInfo) {
        Map<String, TrackerHit> byName = new LinkedHashMap<>();
        if (packageInfo == null) {
            return new ArrayList<>();
        }
        try {
            HashMap<String, RuleType> components = PackageUtils.collectComponentClassNames(packageInfo);
            String[] names = StaticDataset.getTrackerNames();
            AhoCorasick aho = StaticDataset.getSearchableTrackerSignatures();
            for (Map.Entry<String, RuleType> entry : components.entrySet()) {
                String component = entry.getKey();
                if (component == null) continue;
                int[] matches = aho.search(component);
                for (int idx : matches) {
                    if (idx < 0 || idx >= names.length) continue;
                    hit(byName, names[idx]).components.put(component, entry.getValue());
                }
            }
        } catch (Throwable th) {
            // The native index is closed on memory pressure (StaticDataset.cleanup) and rebuilt
            // lazily. A tracker list is never worth taking a screen down for.
            th.printStackTrace();
        }
        return sorted(byName.values());
    }

    /**
     * The manifest scan plus whatever a previous deep scan of <i>this exact version</i> found.
     * Nothing is scanned here — a cached result is read back, or it is not.
     */
    @WorkerThread
    @NonNull
    public static List<TrackerHit> scan(@NonNull Context context, @Nullable PackageInfo packageInfo) {
        List<TrackerHit> hits = fromManifest(packageInfo);
        if (packageInfo == null) {
            return hits;
        }
        Set<String> deep = readCache(context, packageInfo);
        return deep == null ? hits : merge(hits, deep);
    }

    /** Whether a deep scan of this exact version is already on file. */
    public static boolean hasDeepScan(@NonNull Context context, @Nullable PackageInfo packageInfo) {
        return packageInfo != null && readCache(context, packageInfo) != null;
    }

    /**
     * Walk every class in the base APK. Returns the merged list; the deep half is cached.
     *
     * <p>Base APK only — a split carries resources far more often than a tracker, and mounting
     * every split multiplies the cost of the one operation on this page that has any.
     */
    @WorkerThread
    @NonNull
    public static List<TrackerHit> deepScan(@NonNull Context context, @NonNull PackageInfo packageInfo) {
        List<TrackerHit> hits = fromManifest(packageInfo);
        Set<String> found = new LinkedHashSet<>();
        int vfsId = 0;
        try {
            String sourceDir = packageInfo.applicationInfo != null
                    ? packageInfo.applicationInfo.sourceDir : null;
            if (sourceDir == null) {
                return hits;
            }
            File apk = new File(sourceDir);
            vfsId = VirtualFileSystem.mount(Uri.fromFile(apk), Paths.getUnprivileged(apk),
                    ContentType2.DEX.getMimeType());
            DexFileSystem dfs = (DexFileSystem) Objects.requireNonNull(VirtualFileSystem.getFileSystem(vfsId));
            List<String> classes = dfs.getDexClasses().getBaseClassNames();
            String[] names = StaticDataset.getTrackerNames();
            AhoCorasick aho = StaticDataset.getSearchableTrackerSignatures();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String className : classes) {
                if (className == null || className.length() <= 8 || !className.contains(".")) {
                    continue;
                }
                int[] matches = aho.search(className);
                for (int idx : matches) {
                    if (idx < 0 || idx >= names.length) continue;
                    String name = names[idx];
                    found.add(name);
                    Integer n = counts.get(name);
                    counts.put(name, n == null ? 1 : n + 1);
                }
            }
            writeCache(context, packageInfo, found);
            List<TrackerHit> merged = merge(hits, found);
            for (TrackerHit hit : merged) {
                Integer n = counts.get(hit.name);
                if (n == null) {
                    // The cache stores the dataset name, which may carry the second-degree mark.
                    n = counts.get(SECOND_DEGREE + hit.name);
                }
                if (n != null) {
                    hit.classes = n;
                }
            }
            return merged;
        } catch (Throwable th) {
            th.printStackTrace();
            return hits;
        } finally {
            if (vfsId != 0) {
                try {
                    VirtualFileSystem.unmount(vfsId);
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /**
     * Fold the deep scan's names into the manifest hits: a name the manifest already knows keeps
     * its components (and therefore its rung), and one it does not becomes a code-only hit.
     */
    @NonNull
    private static List<TrackerHit> merge(@NonNull List<TrackerHit> hits, @NonNull Set<String> deepNames) {
        Map<String, TrackerHit> byName = new LinkedHashMap<>();
        for (TrackerHit hit : hits) {
            byName.put(hit.name, hit);
        }
        for (String raw : deepNames) {
            hit(byName, raw);
        }
        return sorted(byName.values());
    }

    @NonNull
    private static TrackerHit hit(@NonNull Map<String, TrackerHit> byName, @NonNull String rawName) {
        boolean second = !rawName.isEmpty() && rawName.charAt(0) == SECOND_DEGREE;
        String name = second ? rawName.substring(1) : rawName;
        TrackerHit hit = byName.get(name);
        if (hit == null) {
            hit = new TrackerHit(name, second);
            byName.put(name, hit);
        }
        return hit;
    }

    /** Worst rung first, then alphabetically — the pill row is read left to right. */
    @NonNull
    private static List<TrackerHit> sorted(@NonNull java.util.Collection<TrackerHit> hits) {
        List<TrackerHit> list = new ArrayList<>(hits);
        Collections.sort(list, (a, b) -> {
            int byRung = Integer.compare(b.rung(), a.rung());
            if (byRung != 0) return byRung;
            int bySecond = Boolean.compare(a.secondDegree, b.secondDegree);
            if (bySecond != 0) return bySecond;
            return a.name.compareToIgnoreCase(b.name);
        });
        return list;
    }

    // ── The deep-scan cache ─────────────────────────────────────────────────
    //
    // Keyed by package, stamped with lastUpdateTime: an update invalidates it by itself rather
    // than by anyone remembering to clear it. Derived data with no decision in it, so it is
    // excluded from Export/Import — a cache that travels is a cache that lies on arrival.

    @Nullable
    private static Set<String> readCache(@NonNull Context context, @NonNull PackageInfo packageInfo) {
        String value = prefs(context).getString(packageInfo.packageName, null);
        if (value == null) {
            return null;
        }
        int nl = value.indexOf('\n');
        String stamp = nl < 0 ? value : value.substring(0, nl);
        if (!stamp.equals(String.valueOf(packageInfo.lastUpdateTime))) {
            return null;
        }
        Set<String> names = new LinkedHashSet<>();
        if (nl >= 0 && nl + 1 < value.length()) {
            for (String name : value.substring(nl + 1).split("\n")) {
                if (!TextUtils.isEmpty(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static void writeCache(@NonNull Context context, @NonNull PackageInfo packageInfo,
                                   @NonNull Set<String> names) {
        StringBuilder sb = new StringBuilder().append(packageInfo.lastUpdateTime);
        for (String name : names) {
            sb.append('\n').append(name);
        }
        prefs(context).edit().putString(packageInfo.packageName, sb.toString()).apply();
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }
}

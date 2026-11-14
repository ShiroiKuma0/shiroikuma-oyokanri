// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.scanner.vt;

import android.content.Context;
import android.net.Uri;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import io.github.muntashirakon.AppManager.db.entity.VtScanAttempt;
import io.github.muntashirakon.AppManager.settings.Prefs;
import io.github.muntashirakon.AppManager.utils.DigestUtils;
import io.github.muntashirakon.io.IoUtils;
import io.github.muntashirakon.io.Path;
import io.github.muntashirakon.io.Paths;

/**
 * Fork (白い熊, APK-vetting chain, item 3): a {@code .apks} / {@code .xapk} / {@code .apkm} file is a
 * plain ZIP to VirusTotal and is analysed weakly, so it is unpacked locally and each <b>code-bearing</b>
 * APK is scanned by its own hash — the base, the native-library (ABI) split and feature splits. The
 * language and density splits are skipped (they carry no code). The bundle itself is recorded as a
 * container row ({@link io.github.muntashirakon.AppManager.db.entity.VtFile#isBundle}) and is never
 * uploaded; the report screen shows the parts under it with the worst part's verdict on top.
 *
 * <p>OBB archives inside the bundle are listed locally (embedded {@code .dex}/{@code .so}/{@code .apk}/
 * {@code .jar}) for the record only — never uploaded.
 */
public final class VtBundle {
    private VtBundle() {}

    private static final int MAX_PARTS = 64;

    private static final String[] ABI_TOKENS = {
            "arm64_v8a", "armeabi_v7a", "armeabi", "x86_64", "x86", "mips64", "mips"
    };

    public static boolean isBundle(@Nullable String name, @Nullable String mimeType) {
        if (name != null) {
            String n = name.toLowerCase(Locale.ROOT);
            if (n.endsWith(".apks") || n.endsWith(".xapk") || n.endsWith(".apkm")) return true;
        }
        return false;
    }

    /**
     * Observes the bundle as a container and enqueues a scan for each code-bearing part. Returns the
     * container's VtFile id, or 0 on failure.
     */
    @WorkerThread
    public static long scan(@NonNull Context context, @NonNull VtScanRepository repository,
                            @NonNull Path bundlePath, @Nullable String displayName,
                            @Nullable String mimeType) throws IOException {
        Pair<String, String>[] digests = DigestUtils.getDigests(bundlePath);
        VtScanRepository.ObservedFile container = repository.observePath(bundlePath,
                bundlePath.getUri().toString(), displayName, mimeType,
                VtScanRepository.SOURCE_SCAN_REQUEST, digests);
        repository.markBundleContainer(container.file.id);
        String bundleSha = container.file.sha256;

        String absolutePath = "file".equals(bundlePath.getUri().getScheme())
                ? bundlePath.getUri().getPath() : null;
        File work = new File(context.getCacheDir(), "vt_bundle/" + bundleSha);
        //noinspection ResultOfMethodCallIgnored
        work.mkdirs();

        boolean started = false;
        List<String> obbCode = new ArrayList<>();
        int parts = 0;
        if (absolutePath != null) {
            try (ZipFile zip = new ZipFile(absolutePath)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements() && parts < MAX_PARTS) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".apk")) {
                        if (!isCodeBearing(name)) continue;
                        File extracted = new File(work, safeName(name));
                        try (InputStream is = zip.getInputStream(entry)) {
                            copyTo(is, extracted);
                        }
                        if (enqueuePart(repository, extracted, baseName(name), bundleSha)) {
                            started = true;
                        }
                        parts++;
                    } else if (lower.endsWith(".obb")) {
                        try (InputStream is = zip.getInputStream(entry)) {
                            listObbCode(is, obbCode);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
        writeContainerLocalAnalysis(repository, container.file.id, parts, obbCode);
        if (started) {
            VtScanService.start(context);
        }
        return container.file.id;
    }

    @WorkerThread
    private static boolean enqueuePart(@NonNull VtScanRepository repository, @NonNull File file,
                                       @NonNull String displayName, @NonNull String bundleSha)
            throws IOException {
        Path path = Paths.get(file);
        Pair<String, String>[] digests = DigestUtils.getDigests(path);
        VtScanRepository.ObservedFile observed = repository.observePath(path, path.getUri().toString(),
                displayName, "application/vnd.android.package-archive",
                VtScanRepository.SOURCE_BUNDLE_PART, digests);
        repository.setBundleOf(observed.file.id, bundleSha);
        VtScanAttempt attempt = repository.createInitialAttempt(observed, Prefs.VirusTotal.promptBeforeUpload());
        return attempt != null && VtScanAttempt.STATUS_QUEUED.equals(attempt.status);
    }

    /**
     * A split carries code unless it is a language or density split. The classification is by the
     * {@code config.<token>} suffix: an ABI token is kept (native libraries), a density token
     * ({@code *dpi}) or a bare language token is skipped, and everything else (base, feature splits,
     * unknown) is kept — being wrong toward scanning is the safe side.
     */
    static boolean isCodeBearing(@NonNull String entryName) {
        String base = baseName(entryName).toLowerCase(Locale.ROOT);
        if (base.endsWith(".apk")) base = base.substring(0, base.length() - 4);
        int idx = base.lastIndexOf("config.");
        if (idx < 0) idx = base.lastIndexOf("config_");
        if (idx < 0) {
            // base.apk, split_<feature>.apk, standalone — code.
            return true;
        }
        String token = base.substring(idx + "config.".length());
        // Keep only the final token (e.g. "arm64_v8a", "en", "xxhdpi").
        int dot = token.indexOf('.');
        if (dot >= 0) token = token.substring(0, dot);
        if (token.isEmpty()) return true;
        for (String abi : ABI_TOKENS) {
            if (token.equals(abi)) return true; // native-library split — code
        }
        if (token.endsWith("dpi")) return false; // density split
        if (token.matches("[a-z]{2,3}(_[a-z0-9]{2,4})?")) return false; // language split
        return true; // unknown config — scan it to be safe
    }

    @NonNull
    private static String baseName(@NonNull String entryName) {
        int slash = Math.max(entryName.lastIndexOf('/'), entryName.lastIndexOf('\\'));
        return slash >= 0 ? entryName.substring(slash + 1) : entryName;
    }

    @NonNull
    private static String safeName(@NonNull String entryName) {
        return baseName(entryName).replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void copyTo(@NonNull InputStream is, @NonNull File dest) throws IOException {
        try (java.io.OutputStream os = new java.io.FileOutputStream(dest)) {
            IoUtils.copy(is, os);
        }
    }

    private static void listObbCode(@NonNull InputStream obbStream, @NonNull List<String> out) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(obbStream)) {
            ZipEntry e;
            int count = 0;
            while ((e = zis.getNextEntry()) != null && count < 200) {
                String n = e.getName().toLowerCase(Locale.ROOT);
                if (n.endsWith(".dex") || n.endsWith(".so") || n.endsWith(".apk") || n.endsWith(".jar")) {
                    out.add(e.getName());
                    count++;
                }
            }
        }
    }

    @WorkerThread
    private static void writeContainerLocalAnalysis(@NonNull VtScanRepository repository, long fileId,
                                                    int partCount, @NonNull List<String> obbCode) {
        try {
            JSONObject local = new JSONObject();
            local.put("bundle", true);
            local.put("code_parts", partCount);
            if (!obbCode.isEmpty()) {
                local.put("obb_code", new JSONArray(obbCode));
            }
            repository.setLocalAnalysisJson(fileId, local.toString());
        } catch (Throwable ignored) {
        }
    }
}

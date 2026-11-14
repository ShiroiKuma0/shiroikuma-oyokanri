// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.scanner.vt;

import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

import io.github.muntashirakon.AppManager.settings.FeatureController;
import io.github.muntashirakon.AppManager.settings.Prefs;
import io.github.muntashirakon.AppManager.utils.CpuUtils;
import io.github.muntashirakon.AppManager.utils.ExUtils;
import io.github.muntashirakon.io.IoUtils;
import io.github.muntashirakon.io.Path;

public class VirusTotal {
    public interface FullScanResponseInterface {
        boolean uploadFile();

        void onUploadInitiated();

        void onUploadCompleted(@NonNull String permalink);

        void onReportReceived(@NonNull VtFileReport report);
    }

    public static class ResponseV3<T> {
        @Nullable
        public final T response;
        @Nullable
        public final VtError error;
        public final int httpCode;

        public ResponseV3(@Nullable T response, @Nullable VtError error) {
            // The params are mutually exclusive
            assert (response != null && error == null) || (response == null && error != null);
            this.response = response;
            this.error = error;
            if (error != null) {
                httpCode = error.httpErrorCode;
            } else httpCode = HttpURLConnection.HTTP_OK;
        }

        public boolean shouldRetry() {
            // It should only retry when the quota is exceeded, or the resource is not found, or
            // the resource is not yet available
            if (error == null || error.code == null) {
                return false;
            }
            return (error.code.equals("NotAvailableYet")
                    || error.code.equals("NotFoundError")
                    || error.code.equals("QuotaExceededError"));
        }

        public boolean isSuccessful() {
            return response != null && error == null;
        }

        @NonNull
        @Override
        public String toString() {
            return "ResponseV3{" +
                    "response=" + response +
                    ", error=" + error +
                    ", httpCode=" + httpCode +
                    '}';
        }
    }

    protected static final String FORM_DATA_BOUNDARY = "--AppManagerDataBoundary9f3d77ed3a";
    protected static final String API_V3_PREFIX = "https://www.virustotal.com/api/v3";
    protected static final String URL_FILE_UPLOAD = API_V3_PREFIX + "/files";
    protected static final String URL_LARGE_FILE_UPLOAD = API_V3_PREFIX + "/files/upload_url";
    protected static final String URL_FILE_REPORT = API_V3_PREFIX + "/files/%s";
    protected static final String URL_FILE_ANALYSE = API_V3_PREFIX + "/files/%s/analyse";
    protected static final String URL_ANALYSIS = API_V3_PREFIX + "/analyses/%s";

    @Nullable
    public static VirusTotal getInstance() {
        String apiKey = Prefs.VirusTotal.getApiKey();
        if (FeatureController.isVirusTotalEnabled() && apiKey != null) {
            return new VirusTotal(apiKey);
        }
        return null;
    }

    private final String mApiKey;

    public VirusTotal(@NonNull String apiKey) {
        mApiKey = Objects.requireNonNull(apiKey);
    }

    public void fetchFileReportOrScan(@NonNull Path file,
                                      @NonNull String checksum,
                                      @NonNull FullScanResponseInterface response)
            throws IOException {
        ResponseV3<VtFileReport> responseReport = fetchFileReport(checksum);
        if (responseReport.response != null && responseReport.response.hasReport()) {
            // A report is found
            response.onReportReceived(responseReport.response);
            return;
        }
        // No report found: either failed or still queued
        boolean queued = responseReport.response != null && !responseReport.response.hasReport();
        if (!queued && !responseReport.shouldRetry()) {
            // Retry is not available
            throw new FileNotFoundException("Fetch error: " + responseReport.error);
        }
        // Scan or retry
        VtError error = Objects.requireNonNull(responseReport.error);
        boolean waitFirst = false;
        if ("NotFoundError".equals(error.code)) {
            // Initiate scan
            if (!response.uploadFile()) {
                // Scanning disabled
                throw new FileNotFoundException("File not found in VirusTotal.");
            }
            waitFirst = true;
            PowerManager.WakeLock wakeLock = CpuUtils.getPartialWakeLock("vt_upload");
            wakeLock.acquire();
            try {
                long fileSize = file.length();
                if (fileSize > 650_000_000) {
                    throw new IOException("APK is larger than 650 MB.");
                }
                boolean largeFile = fileSize > 32_000_000L;
                response.onUploadInitiated();
                String filename = file.getName();
                ResponseV3<String> uploadResponse;
                try (InputStream is = file.openInputStream()) {
                    uploadResponse = largeFile
                            ? uploadLargeFile(filename, is, fileSize, null)
                            : uploadFile(filename, is, fileSize, null);
                }
                if (uploadResponse.response != null) {
                    response.onUploadCompleted(getPermalink(checksum));
                }
            } finally {
                CpuUtils.releaseWakeLock(wakeLock);
            }
        }
        int waitDuration = 60_000;
        while (queued || responseReport.shouldRetry()) {
            if (waitFirst) {
                // Effectively makes it a do-while loop
                waitFirst = false;
            } else {
                responseReport = fetchFileReport(checksum);
                queued = responseReport.response != null && !responseReport.response.hasReport();
            }
            // Wait for result: First wait for 1 minute, then for 30 seconds
            // We won't do it less than 30 seconds since the API has a limit of 4 request/minute
            SystemClock.sleep(waitDuration);
            // TODO: 23/5/22 Wait duration should be according to the fileSize
            waitDuration = 30_000;
        }
        if (responseReport.response != null) {
            response.onReportReceived(responseReport.response);
        } else {
            throw new IOException("Scan error: " + responseReport.error);
        }
    }

    /**
     * Fork (白い熊, APK-vetting chain, item 1): reports upload progress so the scan service can show
     * it in the notification. {@code total} is the full multipart length, not the file size.
     */
    public interface UploadProgressListener {
        void onProgress(long bytesWritten, long totalBytes);
    }

    @WorkerThread
    @NonNull
    public ResponseV3<String> uploadFile(@NonNull String filename, @NonNull InputStream is,
                                         long fileLength, @Nullable UploadProgressListener progress)
            throws IOException {
        return uploadAnyFile(new URL(URL_FILE_UPLOAD), filename, is, fileLength, null, progress);
    }

    @WorkerThread
    @NonNull
    public ResponseV3<String> uploadLargeFile(@NonNull String filename, @NonNull InputStream is,
                                              long fileLength, @Nullable UploadProgressListener progress)
            throws IOException {
        // First retrieve the one-time upload URL
        URL url = new URL(URL_LARGE_FILE_UPLOAD);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            applyTimeouts(connection);
            connection.setUseCaches(false);
            connection.setRequestMethod("POST");
            connection.setDoInput(true);
            connection.setRequestProperty("accept", "application/json");
            connection.setRequestProperty("x-apikey", mApiKey);
            int status = connection.getResponseCode();
            if (status < 300) {
                URL uploadUrl = getLargeFileUploadUrl(connection);
                return uploadAnyFile(uploadUrl, filename, is, fileLength, null, progress);
            }
            return new ResponseV3<>(null, getErrorResponse(connection));
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Fork (白い熊, APK-vetting chain, item 1): streams the multipart body with a fixed content
     * length (no in-memory buffering of a 300–650 MB file), a counting copy that feeds
     * {@code progress}, connect/read timeouts, and the plain {@code filename="…"} parameter
     * VirusTotal's upload handler expects — never the RFC 5987 {@code filename*} form, and no bogus
     * {@code Content-Transfer-Encoding: chunked} part header.
     */
    @WorkerThread
    @NonNull
    public ResponseV3<String> uploadAnyFile(@NonNull URL uploadUrl, @NonNull String filename,
                                            @NonNull InputStream is, long fileLength,
                                            @Nullable String password,
                                            @Nullable UploadProgressListener progress)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uploadUrl.openConnection();
        try {
            applyTimeouts(connection);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setDoInput(true);
            connection.setRequestProperty("accept", "application/json");
            connection.setRequestProperty("x-apikey", mApiKey);
            connection.setRequestProperty("content-type", "multipart/form-data; boundary=" + FORM_DATA_BOUNDARY);
            byte[] passwordPart = password != null ? textPartBytes("password", password) : EMPTY;
            byte[] fileHeader = filePartHeaderBytes(filename);
            byte[] epilogue = epilogueBytes();
            long total = (long) passwordPart.length + fileHeader.length + fileLength + epilogue.length;
            // Fixed-length streaming: the whole body is written straight to the socket.
            connection.setFixedLengthStreamingMode(total);
            try (OutputStream os = connection.getOutputStream()) {
                if (passwordPart.length > 0) os.write(passwordPart);
                os.write(fileHeader);
                copyCounting(is, os, total, fileHeader.length + passwordPart.length, progress);
                os.write(epilogue);
                os.flush();
            }
            int status = connection.getResponseCode();
            if (status < 300) {
                // Example: { "data": { "type": "analysis", "id": "…" } }
                return new ResponseV3<>(getAnalysisId(connection), null);
            }
            return new ResponseV3<>(null, getErrorResponse(connection));
        } finally {
            connection.disconnect();
        }
    }

    private static final byte[] EMPTY = new byte[0];
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final long PROGRESS_STEP_BYTES = 1_000_000L;

    private static void applyTimeouts(@NonNull HttpURLConnection connection) {
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
    }

    private static void copyCounting(@NonNull InputStream is, @NonNull OutputStream os, long total,
                                     long preWritten, @Nullable UploadProgressListener progress)
            throws IOException {
        byte[] buffer = new byte[IoUtils.DEFAULT_BUFFER_SIZE];
        long written = preWritten;
        long lastReport = 0;
        int length;
        while ((length = is.read(buffer)) != -1) {
            if (Thread.interrupted()) {
                throw new InterruptedIOException("Upload cancelled.");
            }
            os.write(buffer, 0, length);
            written += length;
            if (progress != null && (written - lastReport >= PROGRESS_STEP_BYTES)) {
                lastReport = written;
                progress.onProgress(written, total);
            }
        }
        if (progress != null) {
            progress.onProgress(written, total);
        }
    }

    @WorkerThread
    @NonNull
    public ResponseV3<VtFileReport> fetchFileReport(@NonNull String id) throws IOException {
        URL url = new URL(String.format(Locale.ROOT, URL_FILE_REPORT, id));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            applyTimeouts(connection);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            connection.setDoInput(true);
            // Set headers
            connection.setRequestProperty("accept", "application/json");
            connection.setRequestProperty("x-apikey", mApiKey);
            // Response
            int status = connection.getResponseCode();
            if (status < 300) {
                // Success
                try {
                    JSONObject jsonObject = new JSONObject(getResponseV3(connection));
                    return new ResponseV3<>(new VtFileReport(jsonObject), null);
                } catch (JSONException e) {
                    throw new IOException(e);
                }
            } else {
                // Failed
                return new ResponseV3<>(null, getErrorResponse(connection));
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Requests a new analysis for a file already present in VirusTotal. This does not upload the
     * file again. The returned ID must be polled using {@link #fetchAnalysis(String)}.
     */
    @WorkerThread
    @NonNull
    public ResponseV3<String> requestRescan(@NonNull String fileId) throws IOException {
        URL url = new URL(String.format(Locale.ROOT, URL_FILE_ANALYSE, fileId));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            applyTimeouts(connection);
            connection.setUseCaches(false);
            connection.setRequestMethod("POST");
            connection.setDoInput(true);
            // Set headers
            connection.setRequestProperty("accept", "application/json");
            connection.setRequestProperty("x-apikey", mApiKey);
            // Response
            int status = connection.getResponseCode();
            if (status < 300) {
                // Success
                return new ResponseV3<>(getAnalysisId(connection), null);
            }
            // Failed
            return new ResponseV3<>(null, getErrorResponse(connection));
        } finally {
            connection.disconnect();
        }
    }

    @WorkerThread
    @NonNull
    public ResponseV3<VtAnalysis> fetchAnalysis(@NonNull String analysisId) throws IOException {
        URL url = new URL(String.format(Locale.ROOT, URL_ANALYSIS, analysisId));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            applyTimeouts(connection);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            connection.setDoInput(true);
            // Set headers
            connection.setRequestProperty("accept", "application/json");
            connection.setRequestProperty("x-apikey", mApiKey);
            // Response
            int status = connection.getResponseCode();
            if (status < 300) {
                // Success
                try {
                    return new ResponseV3<>(new VtAnalysis(new JSONObject(getResponseV3(connection))), null);
                } catch (JSONException e) {
                    throw new IOException(e);
                }
            }
            // Failed
            return new ResponseV3<>(null, getErrorResponse(connection));
        } finally {
            connection.disconnect();
        }
    }

    @NonNull
    public static String getPermalink(@NonNull String id) {
        return "https://www.virustotal.com/gui/file/" + id;
    }

    @NonNull
    public static String getAnalysisId(@NonNull HttpURLConnection connection) throws IOException {
        // https://docs.virustotal.com/reference/files-scan
        try {
            JSONObject dataObject = new JSONObject(getResponseV3(connection))
                    .getJSONObject("data");
            assert dataObject.getString("type").equals("analysis");
            return dataObject.getString("id");
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    @NonNull
    public static URL getLargeFileUploadUrl(@NonNull HttpURLConnection connection) throws IOException {
        // https://docs.virustotal.com/reference/files-upload-url
        try {
            return new URL(new JSONObject(getResponseV3(connection)).getString("data"));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    @NonNull
    private static byte[] textPartBytes(@NonNull String key, @NonNull String value) {
        return ("--" + FORM_DATA_BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"" + key + "\"\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    @NonNull
    private static byte[] filePartHeaderBytes(@NonNull String filename) {
        // Plain filename="…" only — VirusTotal's upload handler rejects the RFC 5987 filename*
        // form (per vt-py) — and no Content-Transfer-Encoding part header.
        return ("--" + FORM_DATA_BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + sanitizeFilename(filename) + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
    }

    @NonNull
    private static byte[] epilogueBytes() {
        return ("\r\n--" + FORM_DATA_BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8);
    }

    @NonNull
    private static String sanitizeFilename(@NonNull String filename) {
        // Strip CR/LF and double quotes so they cannot break out of the quoted parameter.
        String cleaned = filename.replaceAll("[\\r\\n\"]", "_").trim();
        return cleaned.isEmpty() ? "file" : cleaned;
    }

    // ── API-key validation and error classification (白い熊, APK-vetting chain, item 10) ──────────

    public enum KeyCheck {
        /** The key authenticated (a report was returned, or the file was simply not found). */
        VALID,
        /** The key was rejected (401 / WrongCredentialsError / AuthenticationRequiredError). */
        INVALID,
        /** The key is fine but the quota is exhausted — never reported as a bad key. */
        QUOTA,
        /** Could not reach VirusTotal to decide. */
        NETWORK_ERROR
    }

    /** The EICAR anti-malware test file's SHA-256 — always present on VirusTotal, so a lookup of it
     *  is the fallback that tells a working key (200 or NotFound) from a rejected one (401). */
    private static final String WELL_KNOWN_SHA256 =
            "275a021bbfb6489e54d471899f7db9d1663fc695ec2fe2a2c4538aabf651fd0f";

    /**
     * One cheap authenticated call to decide whether this instance's key works. Primary: the user
     * object for the key itself; fallback: a lookup of a well-known file hash.
     */
    @WorkerThread
    @NonNull
    public KeyCheck checkApiKey() {
        try {
            URL url = new URL(API_V3_PREFIX + "/users/" + mApiKey);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            try {
                applyTimeouts(connection);
                connection.setUseCaches(false);
                connection.setRequestMethod("GET");
                connection.setDoInput(true);
                connection.setRequestProperty("accept", "application/json");
                connection.setRequestProperty("x-apikey", mApiKey);
                int status = connection.getResponseCode();
                if (status < 300) return KeyCheck.VALID;
                VtError error = getErrorResponse(connection);
                if (isAuthError(error)) return KeyCheck.INVALID;
                if (isQuotaError(error)) return KeyCheck.QUOTA;
                // Anything else (e.g. the user endpoint behaving oddly): fall through to a lookup.
            } finally {
                connection.disconnect();
            }
            ResponseV3<VtFileReport> report = fetchFileReport(WELL_KNOWN_SHA256);
            if (report.isSuccessful()) return KeyCheck.VALID;
            if (isAuthError(report.error)) return KeyCheck.INVALID;
            if (isQuotaError(report.error)) return KeyCheck.QUOTA;
            if (report.error != null && "NotFoundError".equals(report.error.code)) return KeyCheck.VALID;
            return KeyCheck.NETWORK_ERROR;
        } catch (IOException e) {
            e.printStackTrace();
            return KeyCheck.NETWORK_ERROR;
        }
    }

    public static boolean isAuthError(@Nullable VtError error) {
        if (error == null) return false;
        if (error.httpErrorCode == HttpURLConnection.HTTP_UNAUTHORIZED
                || error.httpErrorCode == HttpURLConnection.HTTP_FORBIDDEN) {
            return true;
        }
        return "WrongCredentialsError".equals(error.code)
                || "AuthenticationRequiredError".equals(error.code)
                || "ForbiddenError".equals(error.code)
                || "UserNotActiveError".equals(error.code);
    }

    public static boolean isQuotaError(@Nullable VtError error) {
        if (error == null) return false;
        if (error.httpErrorCode == 429) return true;
        return "QuotaExceededError".equals(error.code)
                || "TooManyRequestsError".equals(error.code);
    }

    @WorkerThread
    @NonNull
    public static String getResponseV3(@NonNull HttpURLConnection connection) throws IOException {
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        return response.toString();
    }

    @WorkerThread
    @NonNull
    public static VtError getErrorResponse(@NonNull HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        // First try input stream
        String inResponse = ExUtils.exceptionAsNull(() -> getResponseV3(connection));
        if (inResponse != null) {
            return new VtError(status, inResponse);
        }
        // Try error stream
        StringBuilder response;
        InputStream errorStream = connection.getErrorStream();
        if (errorStream == null) {
            response = null;
        } else {
            response = new StringBuilder();
            try {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        errorStream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return new VtError(status, response != null ? response.toString() : null);
    }
}

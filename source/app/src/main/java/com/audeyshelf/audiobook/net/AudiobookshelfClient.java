package com.audeyshelf.audiobook.net;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import com.audeyshelf.audiobook.model.Audiobook;
import com.audeyshelf.audiobook.util.StorageFileHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * Lightweight REST client for Audiobookshelf API.
 * Uses standard HttpURLConnection and org.json for zero external runtime overhead on legacy Android.
 */
public class AudiobookshelfClient {

    private static final String TAG = "AudiobookshelfClient";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    public static HttpURLConnection openConnection(String urlStr) throws IOException {
        return openConnection(new URL(urlStr));
    }

    public static HttpURLConnection openConnection(URL url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP && conn instanceof HttpsURLConnection) {
            try {
                ((HttpsURLConnection) conn).setSSLSocketFactory(new TLSSocketFactory());
            } catch (Throwable ignored) {
            }
        }
        return conn;
    }

    private static volatile List<Audiobook> cachedServerBooks = new ArrayList<>();

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static class LibraryInfo {
        public final String id;
        public final String name;
        public final String mediaType;

        public LibraryInfo(String id, String name, String mediaType) {
            this.id = id;
            this.name = name;
            this.mediaType = mediaType;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public interface AuthCallback {
        void onSuccess(String token, String username, List<LibraryInfo> libraries);
        void onError(String message);
    }

    public interface LibrariesCallback {
        void onSuccess(List<LibraryInfo> libraries);
        void onError(String message);
    }

    public interface BooksCallback {
        void onSuccess(List<Audiobook> books);
        void onError(String message);
    }

    public interface DownloadCallback {
        void onProgress(int percent, long bytesRead, long totalBytes);
        void onSuccess(File downloadedFile);
        void onError(String message);
    }

    public interface ItemDetailsCallback {
        void onSuccess(JSONObject itemDetails, String publishedYear);
        void onError(String message);
    }

    public interface ProgressCallback {
        void onSuccess(long serverProgressMs, double durationSec, boolean isFinished);
        void onError(String message);
    }

    public interface SyncCallback {
        void onSuccess();
        void onError(String message);
    }

    public interface PlaybackSessionCallback {
        void onSuccess(String sessionId, long serverProgressMs);
        void onError(String message);
    }

    public interface ItemIdCallback {
        void onResolved(String serverItemId);
    }

    public static String extractPublicationYear(JSONObject obj) {
        if (obj == null) return "";
        // 1. Check publishedYear
        String year = cleanJsonString(obj, "publishedYear");
        if (!year.isEmpty()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(\\d{4})\\b").matcher(year);
            if (m.find()) return m.group(1);
            return year;
        }
        // 2. Check publishedDate (e.g., "2018-05-21" or "2018")
        String date = cleanJsonString(obj, "publishedDate");
        if (!date.isEmpty()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(\\d{4})\\b").matcher(date);
            if (m.find()) return m.group(1);
            return date;
        }
        // 3. Check releaseDate
        String release = cleanJsonString(obj, "releaseDate");
        if (!release.isEmpty()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(\\d{4})\\b").matcher(release);
            if (m.find()) return m.group(1);
            return release;
        }
        return "";
    }

    private static String cleanJsonString(JSONObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return "";
        Object val = obj.opt(key);
        if (val == null) return "";
        String s = val.toString().trim();
        if (s.equalsIgnoreCase("null") || s.equalsIgnoreCase("undefined")) return "";
        return s;
    }

    /**
     * Fetches details of an item from the server, extracting its publication year.
     */
    public void fetchItemDetails(final String serverUrl, final String token, final String itemId, final ItemDetailsCallback callback) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                if (itemId == null || itemId.isEmpty()) {
                    throw new Exception("Invalid item ID");
                }
                String cleanBase = normalizeUrl(serverUrl);
                String itemUrlStr = cleanBase + "/api/items/" + itemId + "?expanded=1";
                conn = openConnection(itemUrlStr);
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("Authorization", "Bearer " + token);

                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    String jsonStr = readStream(conn.getInputStream());
                    JSONObject itemObj = new JSONObject(jsonStr);
                    JSONObject mediaObj = itemObj.optJSONObject("media");
                    String year = "";
                    if (mediaObj != null) {
                        JSONObject meta = mediaObj.optJSONObject("metadata");
                        if (meta != null) {
                            year = extractPublicationYear(meta);
                        }
                        if (year.isEmpty()) {
                            year = extractPublicationYear(mediaObj);
                        }
                    }
                    if (year.isEmpty()) {
                        year = extractPublicationYear(itemObj);
                    }
                    final String finalYear = year;
                    mainHandler.post(() -> {
                        if (callback != null) callback.onSuccess(itemObj, finalYear);
                    });
                } else {
                    mainHandler.post(() -> {
                        if (callback != null) callback.onError("HTTP " + code);
                    });
                }
            } catch (Throwable e) {
                Log.e(TAG, "Error fetching item details", e);
                mainHandler.post(() -> {
                    if (callback != null) callback.onError(e.getMessage());
                });
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    /**
     * Authenticates with Audiobookshelf server using username and password.
     */
    public void login(final String serverUrl, final String username, final String password, final AuthCallback callback) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                String normalizedUrl = normalizeUrl(serverUrl);
                String cleanUrl = normalizedUrl + "/login";
                URL url = new URL(cleanUrl);
                conn = openConnection(url);
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);
                conn.setDoInput(true);

                JSONObject body = new JSONObject();
                body.put("username", username);
                body.put("password", password);

                byte[] postBytes = body.toString().getBytes(UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(postBytes);
                    os.flush();
                }

                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    String responseStr = readStream(conn.getInputStream());
                    JSONObject json = new JSONObject(responseStr);
                    JSONObject userObj = json.optJSONObject("user");
                    String token = userObj != null ? userObj.optString("token") : "";
                    String returnedUsername = userObj != null ? userObj.optString("username", username) : username;

                    if (token.isEmpty()) {
                        postError(callback, "No authorization token returned by server.");
                        return;
                    }

                    // Fetch user's libraries immediately
                    List<LibraryInfo> libraries = fetchLibrariesInternal(normalizedUrl, token);
                    postSuccess(callback, token, returnedUsername, libraries);
                } else {
                    String errStr = readStream(conn.getErrorStream());
                    String errorMsg = "Login failed (HTTP " + code + ")";
                    if (errStr != null && !errStr.isEmpty()) {
                        try {
                            JSONObject errJson = new JSONObject(errStr);
                            if (errJson.has("error")) {
                                errorMsg = errJson.getString("error");
                            }
                        } catch (Exception ignored) {
                            errorMsg = errStr;
                        }
                    }
                    postError(callback, errorMsg);
                }
            } catch (Throwable e) {
                Log.e(TAG, "Login error", e);
                postError(callback, "Connection failed: " + e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    /**
     * Retrieves libraries from the server.
     */
    public void getLibraries(final String serverUrl, final String token, final LibrariesCallback callback) {
        executor.execute(() -> {
            try {
                List<LibraryInfo> libraries = fetchLibrariesInternal(serverUrl, token);
                mainHandler.post(() -> {
                    if (callback != null) callback.onSuccess(libraries);
                });
            } catch (Throwable e) {
                Log.e(TAG, "Error fetching libraries", e);
                mainHandler.post(() -> {
                    if (callback != null) callback.onError(e.getMessage());
                });
            }
        });
    }

    private List<LibraryInfo> fetchLibrariesInternal(String serverUrl, String token) throws Exception {
        String cleanUrl = normalizeUrl(serverUrl) + "/api/libraries";
        URL url = new URL(cleanUrl);
        HttpURLConnection conn = openConnection(url);
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/json");

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                String responseStr = readStream(conn.getInputStream());
                JSONObject json = new JSONObject(responseStr);
                JSONArray libsArr = json.optJSONArray("libraries");
                List<LibraryInfo> list = new ArrayList<>();
                if (libsArr != null) {
                    for (int i = 0; i < libsArr.length(); i++) {
                        JSONObject libObj = libsArr.getJSONObject(i);
                        String id = libObj.optString("id");
                        String name = libObj.optString("name", "Unnamed Library");
                        String mediaType = libObj.optString("mediaType", "book");
                        list.add(new LibraryInfo(id, name, mediaType));
                    }
                }
                return list;
            } else {
                throw new Exception("Server returned HTTP " + code);
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Retrieves all audiobooks for a given library.
     */
    public void getLibraryBooks(final String serverUrl, final String token, final String libraryId, final BooksCallback callback) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                String cleanBase = normalizeUrl(serverUrl);
                String cleanUrl = cleanBase + "/api/libraries/" + libraryId + "/items?limit=0";
                URL url = new URL(cleanUrl);
                conn = openConnection(url);
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("Accept", "application/json");

                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    String responseStr = readStream(conn.getInputStream());
                    JSONObject json = new JSONObject(responseStr);
                    JSONArray results = json.optJSONArray("results");
                    List<Audiobook> books = new ArrayList<>();

                    if (results != null) {
                        for (int i = 0; i < results.length(); i++) {
                            JSONObject item = results.getJSONObject(i);
                            String itemId = item.optString("id");
                            JSONObject media = item.optJSONObject("media");

                            String title = "Untitled";
                            String author = "Unknown Author";
                            String publishedDate = "";
                            long durationMs = 0;

                            if (media != null) {
                                JSONObject meta = media.optJSONObject("metadata");
                                if (meta != null) {
                                    title = meta.optString("title", "Untitled");
                                    author = meta.optString("authorName", "");
                                    if (author.isEmpty() && meta.has("authors")) {
                                        JSONArray authorsArr = meta.optJSONArray("authors");
                                        if (authorsArr != null && authorsArr.length() > 0) {
                                            JSONObject first = authorsArr.optJSONObject(0);
                                            if (first != null) {
                                                author = first.optString("name", "Unknown Author");
                                            }
                                        }
                                    }
                                    if (author.isEmpty()) {
                                        author = "Unknown Author";
                                    }
                                    publishedDate = extractPublicationYear(meta);
                                    if (publishedDate.isEmpty() && media != null) {
                                        publishedDate = extractPublicationYear(media);
                                    }
                                    if (publishedDate.isEmpty() && item != null) {
                                        publishedDate = extractPublicationYear(item);
                                    }
                                    double durSec = meta.optDouble("duration", 0);
                                    if (durSec <= 0 && media.has("duration")) {
                                        durSec = media.optDouble("duration", 0);
                                    }
                                    durationMs = (long) (durSec * 1000);
                                }
                            }

                            // Cover URL with auth token
                            String coverUrl = cleanBase + "/api/items/" + itemId + "/cover?token=" + token;

                            // Check progress
                            long serverProgressMs = 0;
                            JSONObject progressObj = item.optJSONObject("userMediaProgress");
                            if (progressObj != null) {
                                double curTimeSec = progressObj.optDouble("currentTime", 0);
                                serverProgressMs = (long) (curTimeSec * 1000);
                            }

                            Audiobook book = new Audiobook(itemId, title, author, durationMs, coverUrl, serverProgressMs, publishedDate);
                            books.add(book);
                        }
                    }

                    cachedServerBooks = books;

                    mainHandler.post(() -> {
                        if (callback != null) callback.onSuccess(books);
                    });
                } else {
                    mainHandler.post(() -> {
                        if (callback != null) callback.onError("Failed to fetch books: HTTP " + code);
                    });
                }
            } catch (Throwable e) {
                Log.e(TAG, "Error fetching library books", e);
                mainHandler.post(() -> {
                    if (callback != null) callback.onError("Error loading books: " + e.getMessage());
                });
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    /**
     * Downloads an audiobook file from Audiobookshelf server into the destination directory.
     */
    public void downloadAudiobook(final String serverUrl, final String token, final Audiobook book, final File destinationDir, final DownloadCallback callback) {
        downloadAudiobook(null, serverUrl, token, book, destinationDir, null, callback);
    }

    public void downloadAudiobook(final Context context, final String serverUrl, final String token, final Audiobook book, final File destinationDir, final String treeUriString, final DownloadCallback callback) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            InputStream is = null;
            OutputStream fos = null;
            File tempFile = null;
            boolean wroteViaDocumentFile = false;
            try {
                if (book == null || book.getServerItemId() == null || book.getServerItemId().isEmpty()) {
                    throw new Exception("Invalid audiobook for download");
                }

                String cleanBase = normalizeUrl(serverUrl);
                String itemId = book.getServerItemId();

                String downloadUrlStr = null;
                String targetFileName = null;

                try {
                    String itemUrlStr = cleanBase + "/api/items/" + itemId + "?expanded=1";
                    HttpURLConnection itemConn = openConnection(itemUrlStr);
                    itemConn.setRequestMethod("GET");
                    itemConn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    itemConn.setReadTimeout(READ_TIMEOUT_MS);
                    itemConn.setRequestProperty("Authorization", "Bearer " + token);
                    if (itemConn.getResponseCode() >= 200 && itemConn.getResponseCode() < 300) {
                        String itemJsonStr = readStream(itemConn.getInputStream());
                        JSONObject itemObj = new JSONObject(itemJsonStr);
                        JSONObject mediaObj = itemObj.optJSONObject("media");
                        if (mediaObj != null) {
                            JSONArray audioFiles = mediaObj.optJSONArray("audioFiles");
                            if (audioFiles != null && audioFiles.length() > 0) {
                                JSONObject first = audioFiles.getJSONObject(0);
                                String ino = first.optString("ino");
                                String contentUrl = first.optString("contentUrl");
                                JSONObject fileMeta = first.optJSONObject("metadata");
                                if (fileMeta != null) {
                                    targetFileName = fileMeta.optString("filename");
                                }
                                if (contentUrl != null && !contentUrl.isEmpty()) {
                                    downloadUrlStr = cleanBase + contentUrl;
                                } else if (ino != null && !ino.isEmpty()) {
                                    downloadUrlStr = cleanBase + "/api/items/" + itemId + "/file/" + ino + "/download";
                                }
                            }
                        }
                    }
                    itemConn.disconnect();
                } catch (Exception e) {
                    Log.w(TAG, "Could not fetch expanded item info: " + e.getMessage());
                }

                if (downloadUrlStr == null) {
                    downloadUrlStr = cleanBase + "/api/items/" + itemId + "/download";
                }

                if (!downloadUrlStr.contains("token=")) {
                    downloadUrlStr += (downloadUrlStr.contains("?") ? "&" : "?") + "token=" + token;
                }

                if (targetFileName == null || targetFileName.isEmpty()) {
                    String safeTitle = book.getTitle().replaceAll("[\\\\/:*?\"<>|]", "_").trim();
                    if (safeTitle.isEmpty()) safeTitle = "audiobook_" + itemId;
                    targetFileName = safeTitle + ".m4b";
                }

                File finalFile = new File(destinationDir, targetFileName);

                // Check if SAF DocumentFile stream should be used for removable SD cards
                if (context != null && treeUriString != null && !treeUriString.isEmpty() &&
                        StorageFileHelper.isOnRemovableStorage(destinationDir) &&
                        !StorageFileHelper.isDirectoryActuallyWritable(destinationDir)) {
                    DocumentFile docFile = StorageFileHelper.getOrCreateDocumentFile(context, Uri.parse(treeUriString), destinationDir, targetFileName);
                    if (docFile != null) {
                        fos = context.getContentResolver().openOutputStream(docFile.getUri());
                        wroteViaDocumentFile = (fos != null);
                    }
                }

                // If not using DocumentFile, write via standard FileOutputStream
                if (fos == null) {
                    if (!destinationDir.exists()) {
                        destinationDir.mkdirs();
                    }
                    tempFile = new File(destinationDir, targetFileName + ".download");
                    if (tempFile.exists()) {
                        tempFile.delete();
                    }
                    fos = new FileOutputStream(tempFile);
                }

                URL url = new URL(downloadUrlStr);
                conn = openConnection(url);
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("Accept-Encoding", "identity");

                int code = conn.getResponseCode();
                // Handle redirection (301, 302, 307)
                if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307) {
                    String redirectUrl = conn.getHeaderField("Location");
                    if (redirectUrl != null && !redirectUrl.isEmpty()) {
                        conn.disconnect();
                        if (!redirectUrl.startsWith("http")) {
                            redirectUrl = cleanBase + (redirectUrl.startsWith("/") ? "" : "/") + redirectUrl;
                        }
                        if (!redirectUrl.contains("token=")) {
                            redirectUrl += (redirectUrl.contains("?") ? "&" : "?") + "token=" + token;
                        }
                        conn = openConnection(redirectUrl);
                        conn.setRequestProperty("Authorization", "Bearer " + token);
                        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                        conn.setReadTimeout(READ_TIMEOUT_MS);
                        code = conn.getResponseCode();
                    }
                }

                if (code < 200 || code >= 300) {
                    throw new Exception("Server returned HTTP " + code);
                }

                long totalBytes = conn.getContentLength();
                is = conn.getInputStream();

                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalRead = 0;
                int lastReportedPercent = -1;

                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;

                    if (totalBytes > 0) {
                        int percent = (int) ((totalRead * 100) / totalBytes);
                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent;
                            final int reportPct = percent;
                            final long curRead = totalRead;
                            mainHandler.post(() -> {
                                if (callback != null) callback.onProgress(reportPct, curRead, totalBytes);
                            });
                        }
                    }
                }

                fos.flush();
                fos.close();
                fos = null;
                is.close();
                is = null;

                if (!wroteViaDocumentFile && tempFile != null) {
                    if (finalFile.exists()) {
                        finalFile.delete();
                    }
                    if (!tempFile.renameTo(finalFile)) {
                        throw new Exception("Failed to rename temporary download file to " + finalFile.getName());
                    }
                }

                if (context != null) {
                    MediaScannerConnection.scanFile(context, new String[]{finalFile.getAbsolutePath()}, null, null);
                }

                final File resultFile = finalFile;
                mainHandler.post(() -> {
                    if (callback != null) callback.onSuccess(resultFile);
                });

            } catch (Exception e) {
                Log.e(TAG, "Download failed", e);
                if (tempFile != null && tempFile.exists()) {
                    tempFile.delete();
                }
                final String msg = e.getMessage() != null ? e.getMessage() : "Unknown download error";
                mainHandler.post(() -> {
                    if (callback != null) callback.onError(msg);
                });
            } finally {
                if (fos != null) {
                    try { fos.close(); } catch (Exception ignored) {}
                }
                if (is != null) {
                    try { is.close(); } catch (Exception ignored) {}
                }
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    /**
     * Safely sets the HTTP request method on HttpURLConnection, with reflection fallback for PATCH
     * on older Android/Java platforms that do not include PATCH in HttpURLConnection's valid methods.
     */
    public static void setHttpMethod(HttpURLConnection conn, String method) throws Exception {
        try {
            conn.setRequestMethod(method);
        } catch (java.net.ProtocolException pe) {
            try {
                java.lang.reflect.Field methodField = HttpURLConnection.class.getDeclaredField("method");
                methodField.setAccessible(true);
                methodField.set(conn, method);
            } catch (Exception e) {
                try {
                    java.lang.reflect.Field delegateField = conn.getClass().getDeclaredField("delegate");
                    delegateField.setAccessible(true);
                    Object delegate = delegateField.get(conn);
                    java.lang.reflect.Field mField = delegate.getClass().getSuperclass().getDeclaredField("method");
                    mField.setAccessible(true);
                    mField.set(delegate, method);
                } catch (Exception ignored) {
                    throw pe;
                }
            }
        }
    }

    /**
     * Resolves the server item ID for a book by title and author against the server library.
     */
    public void resolveServerItemId(final String serverUrl, final String token, final String libraryId, final String title, final String author, final String filePath, final ItemIdCallback callback) {
        if (callback == null) return;
        List<Audiobook> cache = cachedServerBooks;
        if (cache != null && !cache.isEmpty()) {
            Audiobook probeLocal = new Audiobook(filePath != null ? filePath : "", title, author, 0L, 0L);
            for (Audiobook sb : cache) {
                if (com.audeyshelf.audiobook.ui.LibraryActivity.isMatchingBook(sb, probeLocal)) {
                    callback.onResolved(sb.getServerItemId());
                    return;
                }
            }
        }

        if (libraryId == null || libraryId.isEmpty()) {
            callback.onResolved(null);
            return;
        }

        getLibraryBooks(serverUrl, token, libraryId, new BooksCallback() {
            @Override
            public void onSuccess(List<Audiobook> books) {
                if (books != null) {
                    cachedServerBooks = books;
                    Audiobook probeLocal = new Audiobook(filePath != null ? filePath : "", title, author, 0L, 0L);
                    for (Audiobook sb : books) {
                        if (com.audeyshelf.audiobook.ui.LibraryActivity.isMatchingBook(sb, probeLocal)) {
                            callback.onResolved(sb.getServerItemId());
                            return;
                        }
                    }
                }
                callback.onResolved(null);
            }

            @Override
            public void onError(String message) {
                callback.onResolved(null);
            }
        });
    }

    /**
     * Starts a playback session on the Audiobookshelf server (equivalent to clicking play on the web platform).
     * This registers that the user has started the book and creates the server-side mediaProgress record.
     */
    public void startPlaybackSession(final String serverUrl, final String token, final String itemId, final PlaybackSessionCallback callback) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                if (itemId == null || itemId.isEmpty()) {
                    throw new Exception("Invalid item ID");
                }
                String cleanBase = normalizeUrl(serverUrl);
                String playUrlStr = cleanBase + "/api/items/" + itemId + "/play";
                conn = openConnection(playUrlStr);
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                JSONObject deviceInfo = new JSONObject();
                deviceInfo.put("clientName", "Audeyshelf");
                deviceInfo.put("clientVersion", "1.0");
                deviceInfo.put("deviceId", "audeyshelf-android");
                body.put("deviceInfo", deviceInfo);
                body.put("mediaPlayer", "MediaPlayer");
                body.put("forceDirectPlay", true);

                byte[] postBytes = body.toString().getBytes(UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(postBytes);
                    os.flush();
                }

                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    String respStr = readStream(conn.getInputStream());
                    JSONObject respJson = new JSONObject(respStr);
                    String sessionId = respJson.optString("id", "");
                    double curTimeSec = respJson.optDouble("currentTime", 0);
                    if (curTimeSec <= 0 && respJson.has("mediaProgress")) {
                        JSONObject mp = respJson.optJSONObject("mediaProgress");
                        if (mp != null) {
                            curTimeSec = mp.optDouble("currentTime", 0);
                        }
                    }
                    final String finalSessionId = sessionId;
                    final long serverProgressMs = (long) (curTimeSec * 1000);
                    mainHandler.post(() -> {
                        if (callback != null) callback.onSuccess(finalSessionId, serverProgressMs);
                    });
                } else {
                    mainHandler.post(() -> {
                        if (callback != null) callback.onError("HTTP " + code);
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error starting playback session", e);
                mainHandler.post(() -> {
                    if (callback != null) callback.onError(e.getMessage());
                });
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    /**
     * Fetches current listening progress for a library item from the server.
     */
    public void fetchItemProgress(final String serverUrl, final String token, final String itemId, final ProgressCallback callback) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                if (itemId == null || itemId.isEmpty()) {
                    throw new Exception("Invalid item ID");
                }
                String cleanBase = normalizeUrl(serverUrl);
                long progressMs = 0;
                double durSec = 0;
                boolean isFinished = false;
                boolean found = false;

                // 1. Primary: GET /api/items/{itemId}?expanded=1 (standard across all ABS versions)
                try {
                    String itemUrlStr = cleanBase + "/api/items/" + itemId + "?expanded=1";
                    conn = openConnection(itemUrlStr);
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    conn.setReadTimeout(READ_TIMEOUT_MS);
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                    conn.setRequestProperty("Accept", "application/json");

                    int code = conn.getResponseCode();
                    if (code >= 200 && code < 300) {
                        String itemStr = readStream(conn.getInputStream());
                        JSONObject itemObj = new JSONObject(itemStr);
                        JSONObject progressObj = itemObj.optJSONObject("userMediaProgress");
                        JSONObject mediaObj = itemObj.optJSONObject("media");
                        if (mediaObj != null) {
                            durSec = mediaObj.optDouble("duration", 0);
                        }
                        if (progressObj != null) {
                            double curTimeSec = progressObj.optDouble("currentTime", 0);
                            durSec = progressObj.optDouble("duration", durSec);
                            isFinished = progressObj.optBoolean("isFinished", false);
                            progressMs = (long) (curTimeSec * 1000);
                        }
                        found = true;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "GET /api/items/" + itemId + " failed: " + e.getMessage());
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                        conn = null;
                    }
                }

                // 2. Fallback: GET /api/me/progress/{itemId}
                if (!found || progressMs <= 0) {
                    try {
                        String progressUrlStr = cleanBase + "/api/me/progress/" + itemId;
                        conn = openConnection(progressUrlStr);
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                        conn.setReadTimeout(READ_TIMEOUT_MS);
                        conn.setRequestProperty("Authorization", "Bearer " + token);
                        conn.setRequestProperty("Accept", "application/json");

                        int code = conn.getResponseCode();
                        if (code >= 200 && code < 300) {
                            String jsonStr = readStream(conn.getInputStream());
                            if (jsonStr != null && !jsonStr.isEmpty() && !jsonStr.equals("null")) {
                                JSONObject progressObj = new JSONObject(jsonStr);
                                double curTimeSec = progressObj.optDouble("currentTime", 0);
                                durSec = progressObj.optDouble("duration", durSec);
                                isFinished = progressObj.optBoolean("isFinished", isFinished);
                                progressMs = (long) (curTimeSec * 1000);
                                found = true;
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (conn != null) {
                            conn.disconnect();
                            conn = null;
                        }
                    }
                }

                final long finalMs = progressMs;
                final double finalDur = durSec;
                final boolean finalFinished = isFinished;
                mainHandler.post(() -> {
                    if (callback != null) callback.onSuccess(finalMs, finalDur, finalFinished);
                });
            } catch (Exception e) {
                Log.e(TAG, "Error fetching item progress", e);
                mainHandler.post(() -> {
                    if (callback != null) callback.onError(e.getMessage());
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    /**
     * Updates playback progress for a library item on the server.
     * Uses POST /api/session/local as primary (ensures the server creates/updates the mediaProgress record
     * and shows it in Continue Listening on web), with fallbacks to /api/session/{sessionId}/sync and /api/me/progress/{itemId}.
     */
    public void updateItemProgress(final String serverUrl, final String token, final String itemId, final String sessionId, final long positionMs, final long durationMs, final long sessionStartedAt, final SyncCallback callback) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                if (itemId == null || itemId.isEmpty()) {
                    throw new Exception("Invalid item ID");
                }
                String cleanBase = normalizeUrl(serverUrl);
                double curTimeSec = positionMs / 1000.0;
                double durSec = durationMs / 1000.0;
                double progressRatio = (durSec > 0) ? Math.min(1.0, curTimeSec / durSec) : 0.0;
                boolean isFinished = (durSec > 0 && curTimeSec >= durSec);
                String sid = (sessionId != null && !sessionId.isEmpty()) ? sessionId : java.util.UUID.randomUUID().toString();
                long startMs = (sessionStartedAt > 0) ? sessionStartedAt : (System.currentTimeMillis() - 30000L);

                boolean success = false;

                // 1. Primary: POST /api/session/local (universal ABS offline/local session sync endpoint)
                try {
                    String localSessionUrl = cleanBase + "/api/session/local";
                    conn = openConnection(localSessionUrl);
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    conn.setReadTimeout(READ_TIMEOUT_MS);
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setDoOutput(true);

                    JSONObject localSession = new JSONObject();
                    localSession.put("id", sid);
                    localSession.put("libraryItemId", itemId);
                    localSession.put("episodeId", JSONObject.NULL);
                    localSession.put("mediaType", "book");
                    localSession.put("currentTime", curTimeSec);
                    localSession.put("timeListening", 30.0);
                    localSession.put("duration", durSec);
                    localSession.put("startedAt", startMs);
                    localSession.put("updatedAt", System.currentTimeMillis());

                    JSONObject deviceInfo = new JSONObject();
                    deviceInfo.put("clientName", "Audeyshelf");
                    deviceInfo.put("deviceId", "audeyshelf-android");
                    localSession.put("deviceInfo", deviceInfo);

                    byte[] postBytes = localSession.toString().getBytes(UTF_8);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(postBytes);
                        os.flush();
                    }

                    int code = conn.getResponseCode();
                    if (code >= 200 && code < 300) {
                        success = true;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "POST /api/session/local failed: " + e.getMessage());
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                        conn = null;
                    }
                }

                // 2. If sessionId is active and local session failed or as companion: POST /api/session/{sessionId}/sync
                if (!success && sessionId != null && !sessionId.isEmpty()) {
                    try {
                        String syncUrl = cleanBase + "/api/session/" + sessionId + "/sync";
                        conn = openConnection(syncUrl);
                        conn.setRequestMethod("POST");
                        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                        conn.setReadTimeout(READ_TIMEOUT_MS);
                        conn.setRequestProperty("Authorization", "Bearer " + token);
                        conn.setRequestProperty("Content-Type", "application/json");
                        conn.setRequestProperty("Accept", "application/json");
                        conn.setDoOutput(true);

                        JSONObject syncBody = new JSONObject();
                        syncBody.put("currentTime", curTimeSec);
                        syncBody.put("timeListening", 30.0);
                        syncBody.put("duration", durSec);

                        byte[] syncBytes = syncBody.toString().getBytes(UTF_8);
                        try (OutputStream os = conn.getOutputStream()) {
                            os.write(syncBytes);
                            os.flush();
                        }

                        int code = conn.getResponseCode();
                        if (code >= 200 && code < 300) {
                            success = true;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "POST /api/session/" + sessionId + "/sync failed: " + e.getMessage());
                    } finally {
                        if (conn != null) {
                            conn.disconnect();
                            conn = null;
                        }
                    }
                }

                // 3. Fallback/Companion: PATCH /api/me/progress/{itemId}
                try {
                    String progressUrl = cleanBase + "/api/me/progress/" + itemId;
                    conn = openConnection(progressUrl);
                    setHttpMethod(conn, "PATCH");
                    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    conn.setReadTimeout(READ_TIMEOUT_MS);
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setRequestProperty("X-HTTP-Method-Override", "PATCH");
                    conn.setDoOutput(true);

                    JSONObject progressBody = new JSONObject();
                    progressBody.put("currentTime", curTimeSec);
                    progressBody.put("duration", durSec);
                    progressBody.put("progress", progressRatio);
                    progressBody.put("isFinished", isFinished);

                    byte[] patchBytes = progressBody.toString().getBytes(UTF_8);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(patchBytes);
                        os.flush();
                    }

                    int code = conn.getResponseCode();
                    if (code >= 200 && code < 300) {
                        success = true;
                    }
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                        conn = null;
                    }
                }

                final boolean finalSuccess = success;
                mainHandler.post(() -> {
                    if (callback != null) {
                        if (finalSuccess) callback.onSuccess();
                        else callback.onError("Failed to sync progress to server");
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error updating item progress", e);
                mainHandler.post(() -> {
                    if (callback != null) callback.onError(e.getMessage());
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    public void updateItemProgress(final String serverUrl, final String token, final String itemId, final long positionMs, final long durationMs, final SyncCallback callback) {
        updateItemProgress(serverUrl, token, itemId, null, positionMs, durationMs, 0L, callback);
    }

    public static String normalizeUrl(String url) {
        if (url == null) return "";
        String trimmed = url.trim();
        if (trimmed.isEmpty()) return "";
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "http://" + trimmed;
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString().trim();
        }
    }

    private void postSuccess(AuthCallback callback, String token, String username, List<LibraryInfo> libraries) {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onSuccess(token, username, libraries);
            }
        });
    }

    private void postError(AuthCallback callback, String message) {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onError(message);
            }
        });
    }
}

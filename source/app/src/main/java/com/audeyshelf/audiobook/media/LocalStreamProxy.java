package com.audeyshelf.audiobook.media;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight in-memory loopback HTTP streaming proxy for MediaPlayer.
 * Used as a fallback when an M4B file on read-only storage (such as an external SD card)
 * has a corrupt AAC dependsOnCoreCoder bit that would crash Stagefright/mediaserver on Android 5.1,
 * and cannot be patched in-place.
 *
 * It streams data directly from disk over 127.0.0.1 with standard HTTP 206 Range support,
 * patching the single corrupt header byte on the fly without writing to disk or copying
 * multi-gigabyte audio files.
 */
public class LocalStreamProxy {

    private static final String TAG = "LocalStreamProxy";
    private static final Pattern RANGE_PATTERN = Pattern.compile("^bytes=(\\d+)-(\\d*)");
    private static final int BUFFER_SIZE = 32 * 1024;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService clientExecutor;
    private final Set<Socket> activeSockets = Collections.synchronizedSet(new HashSet<>());

    private volatile boolean isRunning = false;
    private File currentFile;
    private long patchOffset = -1;
    private byte fixedByte = 0;
    private int port = 0;

    public synchronized String start(File file, long patchOffset, byte fixedByte) throws IOException {
        stop();

        this.currentFile = file;
        this.patchOffset = patchOffset;
        this.fixedByte = fixedByte;

        // Bind exclusively to local loopback interface
        serverSocket = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        this.port = serverSocket.getLocalPort();
        this.isRunning = true;
        this.clientExecutor = Executors.newCachedThreadPool();

        acceptThread = new Thread(this::acceptLoop, "LocalStreamProxy-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        Log.i(TAG, "Started LocalStreamProxy on 127.0.0.1:" + port + " for " + file.getName());
        return "http://127.0.0.1:" + port + "/stream.m4b";
    }

    public synchronized void stop() {
        isRunning = false;

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {
            }
            serverSocket = null;
        }

        synchronized (activeSockets) {
            for (Socket s : activeSockets) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
            activeSockets.clear();
        }

        if (clientExecutor != null) {
            clientExecutor.shutdownNow();
            clientExecutor = null;
        }

        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }

        currentFile = null;
        patchOffset = -1;
        port = 0;
    }

    public boolean isRunning() {
        return isRunning && serverSocket != null && !serverSocket.isClosed();
    }

    public String getUrl() {
        if (!isRunning || port <= 0) return null;
        return "http://127.0.0.1:" + port + "/stream.m4b";
    }

    private void acceptLoop() {
        while (isRunning && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                activeSockets.add(client);
                if (clientExecutor != null && !clientExecutor.isShutdown()) {
                    clientExecutor.execute(() -> {
                        try {
                            handleClient(client);
                        } finally {
                            activeSockets.remove(client);
                            try {
                                client.close();
                            } catch (Exception ignored) {
                            }
                        }
                    });
                } else {
                    client.close();
                }
            } catch (SocketException se) {
                // Expected when serverSocket.close() is called
                break;
            } catch (Exception e) {
                if (isRunning) {
                    Log.w(TAG, "Error accepting client: " + e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        File file = this.currentFile;
        if (file == null || !file.exists()) {
            sendError(socket, 404, "Not Found");
            return;
        }

        try {
            socket.setSoTimeout(15000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String requestLine = in.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;

            String method = parts[0].toUpperCase();
            String rangeHeader = null;

            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.regionMatches(true, 0, "Range:", 0, 6)) {
                    rangeHeader = line.substring(6).trim();
                }
            }

            long fileLength = file.length();
            long start = 0;
            long end = fileLength - 1;
            boolean isPartial = false;

            if (rangeHeader != null) {
                Matcher m = RANGE_PATTERN.matcher(rangeHeader);
                if (m.find()) {
                    String startStr = m.group(1);
                    String endStr = m.group(2);
                    if (startStr != null && !startStr.isEmpty()) {
                        start = Long.parseLong(startStr);
                    }
                    if (endStr != null && !endStr.isEmpty()) {
                        end = Long.parseLong(endStr);
                    }
                    if (start > end || start >= fileLength) {
                        sendError(socket, 416, "Requested Range Not Satisfiable");
                        return;
                    }
                    end = Math.min(end, fileLength - 1);
                    isPartial = true;
                }
            }

            long contentLength = end - start + 1;
            OutputStream rawOut = new BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE);

            StringBuilder header = new StringBuilder();
            if (isPartial) {
                header.append("HTTP/1.1 206 Partial Content\r\n");
                header.append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(fileLength).append("\r\n");
            } else {
                header.append("HTTP/1.1 200 OK\r\n");
            }
            header.append("Content-Type: audio/mp4\r\n");
            header.append("Content-Length: ").append(contentLength).append("\r\n");
            header.append("Accept-Ranges: bytes\r\n");
            header.append("Connection: close\r\n");
            header.append("\r\n");

            rawOut.write(header.toString().getBytes(StandardCharsets.US_ASCII));
            rawOut.flush();

            if ("HEAD".equals(method)) {
                return;
            }

            // Stream file contents and patch on the fly if needed
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(start);
                byte[] buf = new byte[BUFFER_SIZE];
                long remaining = contentLength;
                long currentPos = start;

                while (remaining > 0 && isRunning) {
                    int toRead = (int) Math.min(buf.length, remaining);
                    int read = raf.read(buf, 0, toRead);
                    if (read <= 0) break;

                    // If patchOffset falls within this chunk, apply the patch on-the-fly
                    if (patchOffset >= currentPos && patchOffset < (currentPos + read)) {
                        int indexInBuf = (int) (patchOffset - currentPos);
                        buf[indexInBuf] = fixedByte;
                        Log.d(TAG, "Patched byte on-the-fly at stream pos " + patchOffset);
                    }

                    rawOut.write(buf, 0, read);
                    currentPos += read;
                    remaining -= read;
                }
                rawOut.flush();
            }

        } catch (SocketException se) {
            // Client closed connection (normal during seek or header probe)
        } catch (Exception e) {
            if (isRunning) {
                Log.d(TAG, "Client stream ended: " + e.getMessage());
            }
        }
    }

    private void sendError(Socket socket, int code, String message) {
        try {
            OutputStream out = socket.getOutputStream();
            String res = "HTTP/1.1 " + code + " " + message + "\r\nConnection: close\r\n\r\n";
            out.write(res.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (Exception ignored) {
        }
    }
}

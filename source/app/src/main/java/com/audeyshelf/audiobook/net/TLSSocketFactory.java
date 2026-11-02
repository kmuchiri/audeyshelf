package com.audeyshelf.audiobook.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Custom SSLSocketFactory enabling TLS 1.1 and TLS 1.2 on legacy Android devices (API 16-19).
 * Android 4.1-4.4 supports TLS 1.1 and 1.2, but leaves them disabled by default on HttpsURLConnection.
 */
public class TLSSocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory delegate;

    public TLSSocketFactory() {
        SSLSocketFactory factory = null;
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, null, null);
            factory = context.getSocketFactory();
        } catch (KeyManagementException | NoSuchAlgorithmException e) {
            factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
        this.delegate = factory;
    }

    public TLSSocketFactory(SSLSocketFactory delegate) {
        this.delegate = delegate != null ? delegate : (SSLSocketFactory) SSLSocketFactory.getDefault();
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    private Socket enableTls(Socket socket) {
        if (socket instanceof SSLSocket) {
            SSLSocket ssl = (SSLSocket) socket;
            String[] supported = ssl.getSupportedProtocols();
            if (supported != null && supported.length > 0) {
                List<String> protocols = new ArrayList<>();
                for (String p : supported) {
                    if (!"SSLv3".equalsIgnoreCase(p)) {
                        protocols.add(p);
                    }
                }
                if (!protocols.isEmpty()) {
                    ssl.setEnabledProtocols(protocols.toArray(new String[0]));
                }
            }
        }
        return socket;
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return enableTls(delegate.createSocket(s, host, port, autoClose));
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return enableTls(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return enableTls(delegate.createSocket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return enableTls(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return enableTls(delegate.createSocket(address, port, localAddress, localPort));
    }
}

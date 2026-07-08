package com.themoon.y1.managers;

import android.util.Log;

/**
 * TLS helpers for legacy Android's dormant modern security defaults.
 */
public class NetworkTrustManager {

    private static final String TAG = "NetworkTrustManager";

    // 💡 [Added] A dedicated socket factory that forces legacy Android's dormant modern security (TLS 1.2) to wake up
    public static class TLSSocketFactory extends javax.net.ssl.SSLSocketFactory {
        private javax.net.ssl.SSLSocketFactory internalSSLSocketFactory;

        public TLSSocketFactory() throws java.security.KeyManagementException, java.security.NoSuchAlgorithmException {
            javax.net.ssl.SSLContext context = javax.net.ssl.SSLContext.getInstance("TLS");
            context.init(null, null, null);
            internalSSLSocketFactory = context.getSocketFactory();
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return internalSSLSocketFactory.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return internalSSLSocketFactory.getSupportedCipherSuites();
        }

        @Override
        public java.net.Socket createSocket() throws java.io.IOException {
            return enableTLSOnSocket(internalSSLSocketFactory.createSocket());
        }

        @Override
        public java.net.Socket createSocket(java.net.Socket s, String host, int port, boolean autoClose)
                throws java.io.IOException {
            return enableTLSOnSocket(internalSSLSocketFactory.createSocket(s, host, port, autoClose));
        }

        @Override
        public java.net.Socket createSocket(String host, int port)
                throws java.io.IOException, java.net.UnknownHostException {
            return enableTLSOnSocket(internalSSLSocketFactory.createSocket(host, port));
        }

        @Override
        public java.net.Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort)
                throws java.io.IOException, java.net.UnknownHostException {
            return enableTLSOnSocket(internalSSLSocketFactory.createSocket(host, port, localHost, localPort));
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
            return enableTLSOnSocket(internalSSLSocketFactory.createSocket(host, port));
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress address, int port, java.net.InetAddress localAddress,
                int localPort) throws java.io.IOException {
            return enableTLSOnSocket(internalSSLSocketFactory.createSocket(address, port, localAddress, localPort));
        }

        // 🚀 The key part! Forces every socket that's opened to lock its setting to TLSv1.2.
        private java.net.Socket enableTLSOnSocket(java.net.Socket socket) {
            if (socket instanceof javax.net.ssl.SSLSocket) {
                ((javax.net.ssl.SSLSocket) socket).setEnabledProtocols(new String[] { "TLSv1.1", "TLSv1.2" });
            }
            return socket;
        }
    }

    public static void installTls12TrustAll() {
        try {
            javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                }
            };
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLSv1.2");
            ctx.init(null, trustAll, null);
            final javax.net.ssl.SSLSocketFactory base = ctx.getSocketFactory();
            // Wrap so every socket explicitly enables TLS 1.2 — Android 4.x defaults to TLS 1.0
            javax.net.ssl.SSLSocketFactory tls12 = new javax.net.ssl.SSLSocketFactory() {
                private java.net.Socket patch(java.net.Socket s) {
                    if (s instanceof javax.net.ssl.SSLSocket)
                        ((javax.net.ssl.SSLSocket) s).setEnabledProtocols(new String[]{"TLSv1.2","TLSv1.1","TLSv1"});
                    return s;
                }
                public String[] getDefaultCipherSuites() { return base.getDefaultCipherSuites(); }
                public String[] getSupportedCipherSuites() { return base.getSupportedCipherSuites(); }
                public java.net.Socket createSocket(java.net.Socket s, String h, int p, boolean ac) throws java.io.IOException { return patch(base.createSocket(s,h,p,ac)); }
                public java.net.Socket createSocket(String h, int p) throws java.io.IOException { return patch(base.createSocket(h,p)); }
                public java.net.Socket createSocket(String h, int p, java.net.InetAddress la, int lp) throws java.io.IOException { return patch(base.createSocket(h,p,la,lp)); }
                public java.net.Socket createSocket(java.net.InetAddress h, int p) throws java.io.IOException { return patch(base.createSocket(h,p)); }
                public java.net.Socket createSocket(java.net.InetAddress a, int p, java.net.InetAddress la, int lp) throws java.io.IOException { return patch(base.createSocket(a,p,la,lp)); }
            };
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(tls12);
            javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier(
                new javax.net.ssl.HostnameVerifier() {
                    public boolean verify(String h, javax.net.ssl.SSLSession s) { return true; }
                });
        } catch (Exception ignored) {
            Log.d(TAG, "installTls12TrustAll failed", ignored);
        }
    }
}

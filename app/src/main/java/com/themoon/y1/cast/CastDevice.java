package com.themoon.y1.cast;

/**
 * A Google Cast target discovered on the LAN (a Chromecast Audio, Google Home / Nest
 * speaker, or any device advertising _googlecast._tcp).
 *
 * Immutable value object. {@code id} is the mDNS service instance name (the device's
 * stable UUID); {@code friendlyName} is the human label ("Living Room speaker") pulled
 * from the TXT record's {@code fn=} entry when available, otherwise a cleaned-up id.
 */
public final class CastDevice {
    public final String id;
    public final String friendlyName;
    public final String host;
    public final int port;

    public CastDevice(String id, String friendlyName, String host, int port) {
        this.id = id;
        this.friendlyName = friendlyName;
        this.host = host;
        this.port = port;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CastDevice)) return false;
        CastDevice other = (CastDevice) o;
        // Same box may be re-advertised with a changed friendly name / re-resolved host;
        // identity is the id when we have one, else the host:port endpoint.
        if (id != null && other.id != null) return id.equals(other.id);
        return host != null && host.equals(other.host) && port == other.port;
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : (host != null ? host.hashCode() : 0);
    }

    @Override
    public String toString() {
        return friendlyName + " (" + host + ":" + port + ")";
    }
}

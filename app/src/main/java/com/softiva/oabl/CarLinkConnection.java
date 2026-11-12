package com.softiva.oabl;

/** Transport liveness uses successful Link messages, independently of whether SOC is available. */
final class CarLinkConnection {
    static final long TIMEOUT_MS=30_000;
    private volatile boolean connected;
    private volatile long last=-1;
    void message(long now){last=now;connected=true;}
    void disconnected(){connected=false;}
    boolean connected(long now){return connected&&last>=0&&now>=last&&now-last<=TIMEOUT_MS;}
}

package org.openhab.binding.marstek.internal;

import java.util.concurrent.CompletableFuture;

public class MarstekUdpRequest {
    final String id;
    final String json;
    final CompletableFuture<String> future;
    final long createdAt;

    MarstekUdpRequest(String id, String json) {
        this.id = id;
        this.json = json;
        this.future = new CompletableFuture<>();
        this.createdAt = System.currentTimeMillis();
    }
}

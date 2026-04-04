/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.marstek.internal;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The {@link MarstekUdpClient} is responsible for handling communicating
 * with the marstek device
 *
 * @author matzet1024 - Initial contribution
 */
@NonNullByDefault
public class MarstekUdpClient {
    private final Logger logger = LoggerFactory.getLogger(MarstekUdpClient.class);
    private final DatagramSocket socket;
    private final InetAddress remoteAddress;
    private final int remotePort;
    private final Gson gson = new Gson();

    // Wartende Requests: id -> Future
    private final Map<String, CompletableFuture<String>> pendingRequests = new ConcurrentHashMap<>();
    private final BlockingQueue<MarstekUdpRequest> requestQueue = new LinkedBlockingQueue<>();

    private final ExecutorService executor = Executors.newCachedThreadPool();

    public MarstekUdpClient(int localPort, String remoteHost, int remotePort) throws Exception {
        logger.info("Creating MarstekUdpClient for host {} on Port {} with local port {}", remoteHost, remotePort,
                localPort);
        this.socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(localPort));

        this.remoteAddress = InetAddress.getByName(remoteHost);
        this.remotePort = remotePort;

        startReceiver();
        startWorker();
    }

    private void startReceiver() {
        Thread receiverThread = new Thread(() -> {
            byte[] buffer = new byte[2048];

            while (!socket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    String json = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);

                    String id = extractId(json);
                    logger.trace("received Packet: {} bytes {}, content: {}", packet.getLength(), json.length(),
                            json.replace("\n", " ").replace("\t", ""));
                    logger.debug("Received response {} from {}", id, remoteAddress);

                    if (id != null && pendingRequests.containsKey(id)) {
                        CompletableFuture<String> future = pendingRequests.remove(id);
                        if (future != null) {
                            future.complete(json);
                        }
                    } else {

                    }

                } catch (Exception e) {
                    logger.error("Error reading from Socket", e);
                }
            }
        });

        receiverThread.setDaemon(true);
        receiverThread.start();
    }

    private void startWorker() {
        Thread workerThread = new Thread(() -> {
            while (!socket.isClosed()) {
                try {
                    MarstekUdpRequest request = requestQueue.take();

                    long elapsed = System.currentTimeMillis() - request.createdAt;
                    if (elapsed > 10_000) {
                        request.future
                                .completeExceptionally(new TimeoutException("Timeout before send id=" + request.id));
                        continue;
                    }

                    pendingRequests.put(request.id, request.future);

                    int timeout = 250;
                    int retries = 0;

                    while (retries < 15) {
                        logger.debug("Send/resend Package: {} retries: {} timeout {} {}", request.id, retries, timeout);

                        send(request.json);

                        long remaining = 10_000 - (System.currentTimeMillis() - request.createdAt);
                        if (remaining <= 0) {
                            break;
                        }

                        try {
                            request.future.get(Math.min(timeout, remaining), TimeUnit.MILLISECONDS);
                            break; // Erfolg
                        } catch (TimeoutException e) {
                            retries++;
                            if (timeout < 1000) {
                                timeout *= 2;
                            }
                        }
                    }

                    if (!request.future.isDone()) {
                        pendingRequests.remove(request.id);
                        request.future.completeExceptionally(new TimeoutException("Timeout for id=" + request.id));
                    }

                } catch (Exception e) {
                    logger.error("Worker error", e);
                }
            }
        });

        workerThread.setDaemon(true);
        workerThread.start();
    }

    public String sendAndWaitForResponseWithRetry(String json) throws Exception {
        String id = extractId(json);
        if (id == null) {
            throw new IllegalArgumentException("JSON enthält keine id");
        }

        MarstekUdpRequest request = new MarstekUdpRequest(id, json);

        requestQueue.put(request);

        try {
            return request.future.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.debug("No Reponse for: {}", id);
            throw new TimeoutException("Timeout (queue + send + response) id=" + id);
        }
    }

    private void send(String message) throws Exception {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);

        DatagramPacket packet = new DatagramPacket(data, data.length, remoteAddress, remotePort);

        socket.send(packet);
        logger.trace("Sent Message: {}", message);
    }

    // Simple JSON id extraction (for internal request/response correlation)
    @Nullable
    private String extractId(String jsonString) {
        try {
            JsonObject json = gson.fromJson(jsonString, JsonObject.class);
            if (json != null) {
                JsonElement element = json.get("id");
                if (element != null && !element.isJsonNull()) {
                    return element.getAsString();
                }
            }
        } catch (Exception e) {
            logger.trace("Error extracting id from JSON: {}", e.getMessage());
        }
        return null;
    }

    public void close() {
        logger.info("close Client ");
        socket.close();
        executor.shutdown();
    }
}

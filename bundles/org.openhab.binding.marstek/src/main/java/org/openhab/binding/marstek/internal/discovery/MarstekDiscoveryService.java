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
package org.openhab.binding.marstek.internal.discovery;

import static org.openhab.binding.marstek.internal.marstekBindingConstants.*;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Activate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

// Discovery service disabled - manual thing configuration required
// @NonNullByDefault
// @Component(service = DiscoveryService.class, configurationPid = "discovery.marstek")
@NonNullByDefault
public class MarstekDiscoveryService extends AbstractDiscoveryService {

    private final Logger logger = LoggerFactory.getLogger(MarstekDiscoveryService.class);

    private static final int DEFAULT_DISCOVERY_TIMEOUT = 8; // seconds
    private static final int UDP_TIMEOUT_MS = 1500;
    private static final int MARSTEK_PORT = 30000;

    private final Gson gson = new Gson();

    private final byte[] buffer = new byte[4096];
    @Nullable
    private DatagramSocket socket;

    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = Set.of(THING_TYPE_BATTERY);

    private String[] unicastAddresses = new String[0];

    public MarstekDiscoveryService() {
        super(SUPPORTED_THING_TYPES_UIDS, DEFAULT_DISCOVERY_TIMEOUT, false);
    }

    @Activate
    @Override
    protected void activate(@Nullable Map<String, Object> configProperties) {
        super.activate(configProperties);
        if (configProperties == null) {
            return;
        }
        Object ua = configProperties.get("unicastAddresses");
        if (ua instanceof String) {
            String s = ((String) ua).trim();
            if (!s.isEmpty()) {
                unicastAddresses = s.split("\\s*,\\s*");
            } else {
                unicastAddresses = new String[0];
            }
        }
    }

    @Override
    protected void startScan() {
        logger.debug("Start scan for Marstek devices.");
        discoverThings();
    }

    @Override
    protected void stopScan() {
        logger.debug("Stop scan for Marstek devices.");
        closeSocket();
        super.stopScan();
    }

    private void discoverThings() {
        try {
            String requestJson = "{\"id\":0,\"method\":\"Marstek.GetDevice\",\"params\":{}}";
            byte[] requestBytes = requestJson.getBytes(StandardCharsets.UTF_8);

            if (unicastAddresses.length > 0) {
                logger.debug("Probing configured unicast addresses for Marstek devices: {}",
                        String.join(",", unicastAddresses));
                for (String addr : unicastAddresses) {
                    try {
                        byte[] resp = org.openhab.binding.marstek.internal.MarstekUdpHelper.sendRequest(addr,
                                MARSTEK_PORT, requestBytes, 0, UDP_TIMEOUT_MS);
                        if (resp != null && resp.length > 0) {
                            DatagramPacket received = new DatagramPacket(resp, resp.length, InetAddress.getByName(addr),
                                    MARSTEK_PORT);
                            DiscoveryResult dr = discoveryPacketReceived(received);
                            if (dr != null) {
                                thingDiscovered(dr);
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Error probing {}: {}", addr, e.toString());
                    }
                }
                return;
            }

            startSocket();

            InetAddress broadcast = InetAddress.getByName("255.255.255.255");
            DatagramPacket out = new DatagramPacket(requestBytes, requestBytes.length, broadcast, MARSTEK_PORT);
            DatagramSocket s = this.socket;
            if (s != null) {
                s.setSoTimeout(UDP_TIMEOUT_MS);
                s.send(out);
                logger.debug("Sent Marstek discovery broadcast to {}:{}", broadcast.getHostAddress(), MARSTEK_PORT);
            }

            final DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);

            long end = System.currentTimeMillis() + (DEFAULT_DISCOVERY_TIMEOUT * 1000L);
            while (System.currentTimeMillis() < end) {
                try {
                    DatagramSocket ds = this.socket;
                    if (ds == null) {
                        break;
                    }
                    receivePacket.setLength(buffer.length);
                    ds.receive(receivePacket);
                    if (receivePacket.getLength() > 0) {
                        DiscoveryResult dr = discoveryPacketReceived(receivePacket);
                        if (dr != null) {
                            thingDiscovered(dr);
                        }
                    }
                } catch (SocketTimeoutException e) {
                    // expected periodically
                }
            }
        } catch (IOException e) {
            logger.debug("Error during Marstek discovery: {}", e.toString());
        } finally {
            closeSocket();
        }
    }

    private void startSocket() throws SocketException {
        closeSocket();
        socket = new DatagramSocket(new InetSocketAddress(MARSTEK_PORT));
        socket.setBroadcast(true);
    }

    private void closeSocket() {
        DatagramSocket s = this.socket;
        if (s != null) {
            s.close();
            this.socket = null;
        }
    }

    @Nullable
    private DiscoveryResult discoveryPacketReceived(DatagramPacket packet) {
        String ip = packet.getAddress().getHostAddress();
        String raw = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
        logger.debug("Marstek discovery packet from {}: {}", ip, raw);

        String[] parts = raw.split("\\}\\s*\\{");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i > 0) {
                part = "{" + part;
            }
            if (i < parts.length - 1) {
                part = part + "}";
            }

            try {
                JsonElement elem = gson.fromJson(part, JsonElement.class);
                if (elem != null && elem.isJsonObject()) {
                    JsonObject obj = elem.getAsJsonObject();
                    if (obj.has("result") && obj.get("result").isJsonObject()) {
                        JsonObject result = obj.getAsJsonObject("result");
                        String device = result.has("device") ? result.get("device").getAsString() : "marstek";
                        String wifiMac = result.has("wifi_mac") ? result.get("wifi_mac").getAsString() : null;
                        String bleMac = result.has("ble_mac") ? result.get("ble_mac").getAsString() : null;
                        String ipFromPayload = result.has("ip") ? result.get("ip").getAsString() : ip;

                        String uniqueId = wifiMac != null ? wifiMac : (bleMac != null ? bleMac : ipFromPayload);
                        if (uniqueId == null) {
                            uniqueId = ipFromPayload;
                        }

                        ThingUID thingUID = new ThingUID(THING_TYPE_BATTERY, uniqueId);
                        String label = device + " (" + uniqueId + ")";

                        DiscoveryResultBuilder builder = DiscoveryResultBuilder.create(thingUID).withLabel(label)
                                .withProperty("ip", ipFromPayload).withProperty("device", device);

                        if (wifiMac != null) {
                            builder.withProperty("wifi_mac", wifiMac);
                        }
                        if (bleMac != null) {
                            builder.withProperty("ble_mac", bleMac);
                        }

                        return builder.build();
                    }
                }
            } catch (Exception e) {
                logger.debug("Failed to parse discovery JSON part: {} => {}", part, e.getMessage());
            }
        }

        return null;
    }
}

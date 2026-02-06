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

import static org.junit.Assert.*;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import org.junit.Test;

public class MarstekUdpHelperTest {

    @Test
    public void testSendRequestReceivesResponse() throws Exception {
        final byte[] responsePayload = "pong".getBytes();

        // start a simple UDP server on an ephemeral port
        try (DatagramSocket serverSocket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            serverSocket.setSoTimeout(5000);
            int serverPort = serverSocket.getLocalPort();

            Thread serverThread = new Thread(() -> {
                try {
                    byte[] buf = new byte[1024];
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    serverSocket.receive(packet);

                    // reply with a fixed payload
                    DatagramPacket reply = new DatagramPacket(responsePayload, responsePayload.length,
                            packet.getAddress(), packet.getPort());
                    serverSocket.send(reply);
                } catch (IOException e) {
                    // fail the test by throwing a runtime exception from the thread
                    throw new RuntimeException(e);
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            byte[] request = "ping".getBytes();
            byte[] resp = MarstekUdpHelper.sendRequest("127.0.0.1", serverPort, request, 0, 2000);

            assertNotNull("Expected a response from the UDP helper", resp);
            assertArrayEquals(responsePayload, resp);
        }
    }
}

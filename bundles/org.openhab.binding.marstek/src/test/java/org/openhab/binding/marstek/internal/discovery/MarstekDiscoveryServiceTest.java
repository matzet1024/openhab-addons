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

import static org.junit.Assert.*;
import static org.openhab.binding.marstek.internal.marstekBindingConstants.*;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.openhab.core.config.discovery.DiscoveryResult;

public class MarstekDiscoveryServiceTest {

    private MarstekDiscoveryService discoveryService;

    @Before
    public void setUp() {
        discoveryService = new MarstekDiscoveryService();
    }

    @Test
    public void testActivateWithUnicastAddresses() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("unicastAddresses", "192.168.1.100,192.168.1.101");

        discoveryService.activate(config);

        // Service should be activated without throwing exceptions
        assertNotNull(discoveryService);
    }

    @Test
    public void testActivateWithEmptyConfig() throws Exception {
        Map<String, Object> config = new HashMap<>();

        discoveryService.activate(config);

        // Service should handle empty config gracefully
        assertNotNull(discoveryService);
    }

    @Test
    public void testActivateWithNullConfig() throws Exception {
        discoveryService.activate(null);

        // Service should handle null config gracefully
        assertNotNull(discoveryService);
    }

    @Test
    public void testDiscoveryPacketParsing() throws Exception {
        // Sample JSON response from a real Marstek device
        String jsonResponse = "{\"id\":0,\"src\":\"VenusE 3.0-009b08a68263\",\"result\":{\"device\":\"VenusE 3.0\",\"ver\":144,\"ble_mac\":\"009b08a68263\",\"wifi_mac\":\"48a98ad24a49\",\"wifi_name\":\"Kelimutu\",\"ip\":\"192.168.188.145\"}}";

        byte[] responseBytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
        InetAddress testAddress = InetAddress.getByName("192.168.188.145");
        DatagramPacket packet = new DatagramPacket(responseBytes, responseBytes.length, testAddress, 30000);

        // Use reflection to call the private method for testing
        java.lang.reflect.Method method = MarstekDiscoveryService.class.getDeclaredMethod("discoveryPacketReceived",
                DatagramPacket.class);
        method.setAccessible(true);

        DiscoveryResult result = (DiscoveryResult) method.invoke(discoveryService, packet);

        // Verify the discovery result
        assertNotNull("Discovery result should not be null", result);
        assertEquals("Thing type should be BATTERY", THING_TYPE_BATTERY, result.getThingTypeUID());
        assertEquals("Unique ID should be wifi_mac", "48a98ad24a49", result.getThingUID().getId());

        // Verify properties
        Map<String, Object> properties = result.getProperties();
        assertEquals("VenusE 3.0", properties.get("device"));
        assertEquals("192.168.188.145", properties.get("ip"));
        assertEquals("48a98ad24a49", properties.get("wifi_mac"));
        assertEquals("009b08a68263", properties.get("ble_mac"));
    }

    @Test
    public void testDiscoveryPacketWithMissingWifiMac() throws Exception {
        // Test with only BLE MAC
        String jsonResponse = "{\"id\":0,\"result\":{\"device\":\"TestDevice\",\"ble_mac\":\"aabbccddeeff\",\"ip\":\"192.168.1.50\"}}";

        byte[] responseBytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
        InetAddress testAddress = InetAddress.getByName("192.168.1.50");
        DatagramPacket packet = new DatagramPacket(responseBytes, responseBytes.length, testAddress, 30000);

        java.lang.reflect.Method method = MarstekDiscoveryService.class.getDeclaredMethod("discoveryPacketReceived",
                DatagramPacket.class);
        method.setAccessible(true);

        DiscoveryResult result = (DiscoveryResult) method.invoke(discoveryService, packet);

        assertNotNull("Discovery result should not be null", result);
        assertEquals("Unique ID should fall back to ble_mac", "aabbccddeeff", result.getThingUID().getId());
    }

    @Test
    public void testInvalidJsonHandling() throws Exception {
        // Test with invalid JSON
        String jsonResponse = "This is not valid JSON";

        byte[] responseBytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
        InetAddress testAddress = InetAddress.getByName("192.168.1.1");
        DatagramPacket packet = new DatagramPacket(responseBytes, responseBytes.length, testAddress, 30000);

        java.lang.reflect.Method method = MarstekDiscoveryService.class.getDeclaredMethod("discoveryPacketReceived",
                DatagramPacket.class);
        method.setAccessible(true);

        DiscoveryResult result = (DiscoveryResult) method.invoke(discoveryService, packet);

        // Should handle gracefully and return null
        assertNull("Invalid JSON should return null", result);
    }
}

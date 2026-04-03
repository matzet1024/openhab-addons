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

import static org.openhab.binding.marstek.internal.marstekBindingConstants.*;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The {@link marstekHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author acfischer42 - Initial contribution
 */
@NonNullByDefault
public class marstekHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(marstekHandler.class);
    private final Gson gson = new Gson();

    private @Nullable marstekConfiguration config;
    private @Nullable ScheduledFuture<?> refreshTask;
    private volatile int refreshInterval = 60;
    private volatile boolean disposed = false;
    private volatile int consecutiveFailures = 0;
    private static final int MAX_FAILURES_BEFORE_OFFLINE = 3;

    // Passive mode settings
    private volatile int passivePowerSetting = 0;
    private volatile int passiveCountdownSetting = 300;

    // Manual mode time period storage (4 periods)
    private final TimePeriod[] timePeriods = new TimePeriod[4];

    private @Nullable MarstekUdpClient marstekUdpClient;

    static class TimePeriod {
        boolean enabled = false;
        String start = "00:00";
        String end = "00:00";
        int weekdaysBitmask = 0;
        int power = 0;
    }

    public marstekHandler(Thing thing) {
        super(thing);
        // Initialize time periods
        for (int i = 0; i < timePeriods.length; i++) {
            timePeriods[i] = new TimePeriod();
        }
        logger.debug("marstekHandler created");
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            scheduler.execute(this::refresh);
            return;
        }

        String channelId = channelUID.getId();

        // Handle mode selection commands
        if (CHANNEL_MODE_SELECT.equals(channelId) && command instanceof StringType) {
            String mode = command.toString();
            scheduler.execute(() -> setOperatingMode(mode));
        }

        // Handle passive mode power setting
        else if (CHANNEL_PASSIVE_POWER.equals(channelId) && command instanceof QuantityType) {
            QuantityType<?> quantity = (QuantityType<?>) command;
            QuantityType<?> watts = quantity.toUnit(Units.WATT);
            if (watts != null) {
                passivePowerSetting = watts.intValue();
                logger.debug("Passive power setting updated to {} W", passivePowerSetting);
            }
        }

        // Handle passive mode countdown setting
        else if (CHANNEL_PASSIVE_COUNTDOWN.equals(channelId) && command instanceof QuantityType) {
            QuantityType<?> quantity = (QuantityType<?>) command;
            QuantityType<?> seconds = quantity.toUnit(Units.SECOND);
            if (seconds != null) {
                passiveCountdownSetting = seconds.intValue();
                logger.debug("Passive countdown setting updated to {} s", passiveCountdownSetting);
            }
        }

        // Handle passive mode activation
        else if (CHANNEL_PASSIVE_ACTIVATE.equals(channelId) && command instanceof OnOffType) {
            if (command == OnOffType.ON) {
                scheduler.execute(() -> activatePassiveMode(passivePowerSetting, passiveCountdownSetting));
            }
        }

        // Handle manual mode activation
        else if (CHANNEL_MANUAL_ACTIVATE.equals(channelId) && command instanceof OnOffType) {
            if (command == OnOffType.ON) {
                scheduler.execute(this::activateManualMode);
            }
        }

        // Handle manual mode time period settings
        else if (channelId.startsWith(CHANNEL_GROUP_PERIOD_PREFIX)) {
            handleTimePeriodCommand(channelId, command);
        }
    }

    @Override
    public void initialize() {
        logger.debug("marstekHandler initialize");
        disposed = false;
        consecutiveFailures = 0;
        config = getConfigAs(marstekConfiguration.class);

        if (config != null && config.refreshInterval > 0) {
            refreshInterval = config.refreshInterval;
        }

        updateStatus(ThingStatus.UNKNOWN);

        try {
            if (marstekUdpClient != null) {
                logger.debug("marstekHandler close prev connection");
                // close old Connection
                marstekUdpClient.close();
            }
            logger.debug("marstekHandler create client");
            marstekUdpClient = new MarstekUdpClient(getLocalPort(), getHost(), getPort());

            scheduler.execute(() -> {
                if (disposed) {
                    return;
                }

                // Test connectivity
                boolean thingReachable = testConnection();

                if (thingReachable) {
                    updateStatus(ThingStatus.ONLINE);
                    // Schedule periodic polling
                    refreshTask = scheduler.scheduleWithFixedDelay(this::refresh, 0, Math.max(1, refreshInterval),
                            java.util.concurrent.TimeUnit.SECONDS);
                } else {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "Could not connect to device");
                }
            });

        } catch (Exception e) {
            logger.error("Could not create UDP-Client", e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Could not create UDP-Client");
        }
    }

    @Override
    public void dispose() {
        logger.debug("dispose");
        disposed = true;

        ScheduledFuture<?> task = refreshTask;
        if (task != null) {
            task.cancel(true);
            refreshTask = null;
        }
        this.marstekUdpClient.close();
        super.dispose();
    }

    protected String getHost() {
        return config != null ? config.hostname : "127.0.0.1";
    }

    protected int getPort() {
        return config != null ? config.port : 30000;
    }

    protected int getLocalPort() {
        return config != null ? config.localPort : 0;
    }

    protected @Nullable String sendRequest(String request) throws Exception {
        if (marstekUdpClient != null) {
            return marstekUdpClient.sendAndWaitForResponseWithRetry(request);
        }
        return null;
    }

    protected String createRequestWithID(String id, String method, String params) {
        String idParmString = "\"id\":\"" + id + "\"";
        String paramsStr = Optional.ofNullable(params).orElse("");
        return "{" + idParmString + ",\"method\":\"" + method + "\",\"params\":{" + idParmString
                + (paramsStr.isEmpty() ? "" : "," + params) + "}}";
    }

    protected String createRequest(String method) {
        return createRequest(method, "");
    }

    protected String createRequest(String method, String parms) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(6);
        return createRequestWithID(id, method, parms);
    }

    private boolean testConnection() {
        try {

            logger.debug("Testing connection to Marstek device at {}:{}", getHost(), getPort());

            // Send Marstek.GetDevice request with longer timeout for initialization
            String request = createRequest("Marstek.GetDevice", "\"ble_mac\":\"0\"");
            String response = sendRequest(request);

            if (response != null && response.length() > 0) {
                logger.debug("Connection test successful, received {} bytes", response.length());
                return true;
            } else {
                logger.warn("Connection test failed: No response from device at {}:{}", getHost(), getPort());
                return false;
            }
        } catch (Exception e) {
            logger.warn("Connection test failed with exception: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Refresh data from the Marstek device and update channels.
     */
    private void refresh() {
        logger.debug("marstekHandler refresh {}", disposed);
        if (disposed) {
            return;
        }

        try {

            // Test if device is reachable before querying
            boolean deviceReachable = queryEnergySystemMode();

            if (!deviceReachable) {
                consecutiveFailures++;
                logger.debug("Device unreachable, consecutive failures: {}", consecutiveFailures);

                if (consecutiveFailures >= MAX_FAILURES_BEFORE_OFFLINE) {
                    if (getThing().getStatus() == ThingStatus.ONLINE) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                "No response from device");
                        logger.info("Device went OFFLINE after {} consecutive failures", consecutiveFailures);
                    }
                }
                return;
            }

            // Device responded, reset failure counter
            consecutiveFailures = 0;

            // queryEnergyMeterStatus();
            // queryWifiStatus();
            queryBatteryStatus();
            // PV.GetStatus is only supported on Venus D models, skip for Venus C/E
            // queryPvStatus(host, port, timeoutMs);

            // Update last update timestamp
            safeUpdateState(CHANNEL_LAST_UPDATE, new DateTimeType(ZonedDateTime.now()));

            // If we were offline, mark back online
            if (getThing().getStatus() != ThingStatus.ONLINE) {
                updateStatus(ThingStatus.ONLINE);
                logger.info("Device back ONLINE");
            }
            logger.debug("marstekHandler refresh finished");

        } catch (Exception e) {
            consecutiveFailures++;
            logger.debug("Error refreshing Marstek device: {}, consecutive failures: {}", e.getMessage(),
                    consecutiveFailures);

            if (consecutiveFailures >= MAX_FAILURES_BEFORE_OFFLINE) {
                if (getThing().getStatus() == ThingStatus.ONLINE) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "Communication error: " + e.getMessage());
                    logger.info("Device went OFFLINE after {} consecutive failures: {}", consecutiveFailures,
                            e.getMessage());
                }
            }
        }
    }

    /**
     * Quick test to check if device is reachable
     */
    private boolean testDeviceReachable() {
        try {

            String request = createRequest("Marstek.GetDevice", "\"ble_mac\":\"0\"");
            String response = sendRequest(request);
            return response != null && !response.isEmpty();
        } catch (Exception e) {
            logger.error("Device reachability test failed: {}", e.getMessage());
            return false;
        }
    }

    private void queryBatteryStatus() {
        try {
            logger.debug("Querying Bat.GetStatus from {}:{}", getHost(), getPort());
            String request = createRequest("Bat.GetStatus");
            String response = sendRequest(request);

            if (response != null) {
                logger.trace("Battery response ({} bytes): {}", response.length(),
                        response.replace("\n", " ").replace("\t", ""));
                JsonObject json = gson.fromJson(response, JsonObject.class);
                JsonObject result = json.getAsJsonObject("result");

                if (result != null) {
                    logger.debug("Successfully parsed Bat.GetStatus result");
                    updateNumberChannel(CHANNEL_BATTERY_SOC, result, "soc", Units.PERCENT);
                    updateNumberChannel(CHANNEL_BATTERY_TEMPERATURE, result, "bat_temp", SIUnits.CELSIUS);
                    updateNumberChannel(CHANNEL_BATTERY_CAPACITY, result, "bat_capacity", Units.WATT_HOUR);
                    updateNumberChannel(CHANNEL_BATTERY_RATED_CAPACITY, result, "rated_capacity", Units.WATT_HOUR);
                    updateSwitchChannel(CHANNEL_CHARGING_FLAG, result, "charg_flag");
                    updateSwitchChannel(CHANNEL_DISCHARGING_FLAG, result, "dischrg_flag");
                } else {
                    logger.debug("Bat.GetStatus returned null result");
                }
            } else {
                logger.error("No response from Bat.GetStatus (timeout)");
            }
        } catch (Exception e) {
            logger.error("Error querying battery status: {}", e.getMessage(), e);
        }
    }

    private void queryPvStatus() {
        try {
            String request = createRequest("PV.GetStatus");
            String response = sendRequest(request);

            if (response != null) {
                logger.trace("PV response ({} bytes): {}", response.length(),
                        response.replace("\n", " ").replace("\t", ""));
                JsonObject json = gson.fromJson(response, JsonObject.class);
                JsonObject result = json.getAsJsonObject("result");

                if (result != null) {
                    updateNumberChannel(CHANNEL_PV_POWER, result, "pv_power", Units.WATT);
                    updateNumberChannel(CHANNEL_PV_VOLTAGE, result, "pv_voltage", Units.VOLT);
                    updateNumberChannel(CHANNEL_PV_CURRENT, result, "pv_current", Units.AMPERE);
                }
            } else {
                logger.error("No response from PV.GetStatus (timeout or error)");
            }
        } catch (Exception e) {
            logger.error("Error querying PV status: {}", e.getMessage());
        }
    }

    private boolean queryEnergySystemStatus() {
        try {
            String request = createRequest("ES.GetStatus");
            String response = sendRequest(request);

            if (response != null) {
                logger.trace("ES response ({} bytes): {}", response.length(),
                        response.replace("\n", " ").replace("\t", ""));
                JsonObject json = gson.fromJson(response, JsonObject.class);
                JsonObject result = json.getAsJsonObject("result");

                if (result != null) {
                    updateNumberChannel(CHANNEL_ONGRID_POWER, result, "ongrid_power", Units.WATT);
                    updateNumberChannel(CHANNEL_OFFGRID_POWER, result, "offgrid_power", Units.WATT);
                    updateNumberChannel(CHANNEL_BATTERY_POWER, result, "bat_power", Units.WATT);
                    updateNumberChannel(CHANNEL_TOTAL_PV_ENERGY, result, "total_pv_energy", Units.WATT_HOUR);
                    updateNumberChannel(CHANNEL_TOTAL_GRID_OUTPUT_ENERGY, result, "total_grid_output_energy",
                            Units.WATT_HOUR);
                    updateNumberChannel(CHANNEL_TOTAL_GRID_INPUT_ENERGY, result, "total_grid_input_energy",
                            Units.WATT_HOUR);
                    updateNumberChannel(CHANNEL_TOTAL_LOAD_ENERGY, result, "total_load_energy", Units.WATT_HOUR);
                }
                return true;
            } else {
                logger.error("No response from ES.GetStatus (timeout or error)");
            }
        } catch (Exception e) {
            logger.error("Error querying energy system status: {}", e.getMessage());
        }
        return false;
    }

    private boolean queryEnergySystemMode() {
        try {
            String request = createRequest("ES.GetMode");
            String response = sendRequest(request);

            if (response != null) {
                logger.trace("ES mode response ({} bytes): {}", response.length(),
                        response.replace("\n", " ").replace("\t", ""));
                JsonObject json = gson.fromJson(response, JsonObject.class);
                JsonObject result = json.getAsJsonObject("result");

                if (result != null) {
                    // Extract operating mode
                    if (result.has("mode")) {
                        String mode = result.get("mode").getAsString();
                        safeUpdateState(CHANNEL_OPERATING_MODE, new StringType(mode));
                    }
                    // VenusE devices return additional data in ES.GetMode including bat_soc
                    updateNumberChannel(CHANNEL_BATTERY_SOC, result, "bat_soc", Units.PERCENT);
                    updateNumberChannel(CHANNEL_ONGRID_POWER, result, "ongrid_power", Units.WATT);
                    updateNumberChannel(CHANNEL_OFFGRID_POWER, result, "offgrid_power", Units.WATT);
                    // Note: Phase power and CT state come from EM.GetStatus, not ES.GetMode
                }
                return true;
            } else {
                logger.error("No response from ES.GetMode (timeout or error)");
            }
        } catch (Exception e) {
            logger.error("Error querying energy system mode: {}", e.getMessage());
        }
        return false;
    }

    private void queryEnergyMeterStatus() {
        try {
            String request = createRequest("EM.GetStatus");
            String response = sendRequest(request);

            if (response != null) {
                logger.trace("EM response ({} bytes): {}", response.length(),
                        response.replace("\n", " ").replace("\t", ""));
                JsonObject json = gson.fromJson(response, JsonObject.class);
                JsonObject result = json.getAsJsonObject("result");

                if (result != null) {
                    if (result.has("ct_state")) {
                        int ctState = result.get("ct_state").getAsInt();
                        safeUpdateState(CHANNEL_CT_STATE, ctState == 1 ? OnOffType.ON : OnOffType.OFF);
                    }
                    updateNumberChannel(CHANNEL_PHASE_A_POWER, result, "a_power", Units.WATT);
                    updateNumberChannel(CHANNEL_PHASE_B_POWER, result, "b_power", Units.WATT);
                    updateNumberChannel(CHANNEL_PHASE_C_POWER, result, "c_power", Units.WATT);
                    updateNumberChannel(CHANNEL_TOTAL_METER_POWER, result, "total_power", Units.WATT);
                }
            } else {
                logger.error("No response from EM.GetStatus (timeout or error)");
            }
        } catch (Exception e) {
            logger.error("Error querying energy meter status: {}", e.getMessage());
        }
    }

    private void queryWifiStatus() {
        try {
            String request = createRequest("Wifi.GetStatus");
            String response = sendRequest(request);

            if (response != null) {
                logger.trace("WiFi response ({} bytes): {}", response.length(),
                        response.replace("\n", " ").replace("\t", ""));
                JsonObject json = gson.fromJson(response, JsonObject.class);
                JsonObject result = json.getAsJsonObject("result");

                if (result != null) {
                    if (result.has("rssi")) {
                        safeUpdateState(CHANNEL_WIFI_RSSI, new DecimalType(result.get("rssi").getAsInt()));
                    }
                    if (result.has("ssid") && !result.get("ssid").isJsonNull()) {
                        safeUpdateState(CHANNEL_WIFI_SSID, new StringType(result.get("ssid").getAsString()));
                    }
                    if (result.has("sta_ip") && !result.get("sta_ip").isJsonNull()) {
                        safeUpdateState(CHANNEL_IP_ADDRESS, new StringType(result.get("sta_ip").getAsString()));
                    }
                }
            } else {
                logger.debug("No response from Wifi.GetStatus (timeout or error)");
            }
        } catch (Exception e) {
            logger.debug("Error querying WiFi status: {}", e.getMessage());
        }
    }

    private void updateNumberChannel(String channelId, JsonObject json, String fieldName, javax.measure.Unit<?> unit) {
        if (json.has(fieldName)) {
            JsonElement element = json.get(fieldName);
            if (!element.isJsonNull()) {
                double value = element.getAsDouble();
                safeUpdateState(channelId, new QuantityType<>(value, unit));
            }
        }
    }

    private void updateSwitchChannel(String channelId, JsonObject json, String fieldName) {
        if (json.has(fieldName)) {
            JsonElement element = json.get(fieldName);
            if (!element.isJsonNull()) {
                boolean value = element.getAsBoolean();
                safeUpdateState(channelId, value ? OnOffType.ON : OnOffType.OFF);
            }
        }
    }

    private void safeUpdateState(String channelId, org.openhab.core.types.State state) {
        if (!disposed && getThing().getStatus() == ThingStatus.ONLINE) {
            updateState(channelId, state);
        }
    }

    /**
     * Set device operating mode (Auto, AI, or UPS)
     */
    private void setOperatingMode(String mode) {
        if (disposed) {
            return;
        }

        try {
            String host = config != null ? config.hostname : "127.0.0.1";
            int port = config != null ? config.port : 30000;

            String request;
            if ("Auto".equals(mode)) {
                request = createRequest("ES.SetMode", "\"config\":{\"mode\":\"Auto\",\"auto_cfg\":{\"enable\":1}}");
            } else if ("AI".equals(mode)) {
                request = createRequest("ES.SetMode", "\"config\":{\"mode\":\"AI\",\"ai_cfg\":{\"enable\":1}}");
            } else if ("UPS".equals(mode)) {
                // UPS mode = Manual mode with 24/7 operation at -2500W (charge from grid)
                request = createRequest("ES.SetMode",
                        "\"config\":{\"mode\":\"Manual\",\"manual_cfg\":{\"time_num\":1,\"start_time\":\"00:00\",\"end_time\":\"23:59\",\"week_set\":127,\"power\":-2500,\"enable\":1}}");
            } else {
                logger.warn("Unsupported mode selection: {}", mode);
                return;
            }

            logger.debug("Setting operating mode to: {}", mode);
            String response = sendRequest(request);

            if (response != null) {
                JsonObject json = gson.fromJson(response, JsonObject.class);
                JsonObject result = json.getAsJsonObject("result");

                if (result != null && result.has("set_result")) {
                    boolean success = result.get("set_result").getAsBoolean();
                    if (success) {
                        logger.info("Successfully set operating mode to {}", mode);
                        // Update the operatingMode channel after a short delay
                        scheduler.schedule(this::refresh, 1, java.util.concurrent.TimeUnit.SECONDS);
                    } else {
                        logger.warn("Failed to set operating mode to {}", mode);
                    }
                } else {
                    logger.warn("Unexpected response when setting mode: {}", new String(response));
                }
            } else {
                logger.warn("No response when setting operating mode to {}", mode);
            }
        } catch (Exception e) {
            logger.warn("Error setting operating mode: {}", e.getMessage(), e);
        }
    }

    /**
     * Activate passive mode with specified power and countdown
     */
    private void activatePassiveMode(int power, int countdown) {
        if (disposed) {
            return;
        }

        try {
            String host = config != null ? config.hostname : "127.0.0.1";
            int port = config != null ? config.port : 30000;

            String request = createRequest("ES.SetMode",
                    String.format("\"config\":{\"mode\":\"Passive\",\"passive_cfg\":{\"power\":%d,\"cd_time\":%d}}",
                            power, countdown));

            logger.debug("Activating passive mode with power={} W, countdown={} s", power, countdown);
            String response = sendRequest(request);

            if (response != null) {
                JsonObject json = gson.fromJson(response, JsonObject.class);
                JsonObject result = json.getAsJsonObject("result");

                if (result != null && result.has("set_result")) {
                    boolean success = result.get("set_result").getAsBoolean();
                    if (success) {
                        logger.info("Successfully activated passive mode with power={} W, countdown={} s", power,
                                countdown);
                        // Reset the switch and update mode after a short delay
                        scheduler.schedule(() -> {
                            updateState(CHANNEL_PASSIVE_ACTIVATE, OnOffType.OFF);
                            refresh();
                        }, 1, java.util.concurrent.TimeUnit.SECONDS);
                    } else {
                        logger.warn("Failed to activate passive mode");
                        updateState(CHANNEL_PASSIVE_ACTIVATE, OnOffType.OFF);
                    }
                } else {
                    logger.warn("Unexpected response when activating passive mode: {}", new String(response));
                    updateState(CHANNEL_PASSIVE_ACTIVATE, OnOffType.OFF);
                }
            } else {
                logger.warn("No response when activating passive mode");
                updateState(CHANNEL_PASSIVE_ACTIVATE, OnOffType.OFF);
            }
        } catch (Exception e) {
            logger.warn("Error activating passive mode: {}", e.getMessage(), e);
            updateState(CHANNEL_PASSIVE_ACTIVATE, OnOffType.OFF);
        }
    }

    /**
     * Handle commands for manual mode time period channels
     */
    private void handleTimePeriodCommand(String channelId, Command command) {
        // Parse channel ID: timePeriod1_enabled, timePeriod2_start, etc.
        String[] parts = channelId.split("_", 2);
        if (parts.length != 2) {
            return;
        }

        String groupId = parts[0]; // e.g., "timePeriod1"
        String channelName = parts[1]; // e.g., "enabled"

        // Extract period index (0-3)
        int periodIndex = -1;
        for (int i = 1; i <= 4; i++) {
            if ((CHANNEL_GROUP_PERIOD_PREFIX + i).equals(groupId)) {
                periodIndex = i - 1;
                break;
            }
        }

        if (periodIndex < 0 || periodIndex >= timePeriods.length) {
            return;
        }

        TimePeriod period = timePeriods[periodIndex];

        // Update the appropriate field
        switch (channelName) {
            case CHANNEL_PERIOD_ENABLED:
                if (command instanceof OnOffType) {
                    period.enabled = (command == OnOffType.ON);
                    logger.debug("Period {} enabled: {}", periodIndex + 1, period.enabled);
                }
                break;

            case CHANNEL_PERIOD_START:
                if (command instanceof StringType) {
                    period.start = command.toString();
                    logger.debug("Period {} start time: {}", periodIndex + 1, period.start);
                }
                break;

            case CHANNEL_PERIOD_END:
                if (command instanceof StringType) {
                    period.end = command.toString();
                    logger.debug("Period {} end time: {}", periodIndex + 1, period.end);
                }
                break;

            case CHANNEL_PERIOD_WEEKDAYS:
                if (command instanceof StringType) {
                    period.weekdaysBitmask = weekdaysStringToBitmask(command.toString());
                    logger.debug("Period {} weekdays bitmask: {}", periodIndex + 1, period.weekdaysBitmask);
                }
                break;

            case CHANNEL_PERIOD_POWER:
                if (command instanceof QuantityType) {
                    QuantityType<?> quantity = (QuantityType<?>) command;
                    QuantityType<?> watts = quantity.toUnit(Units.WATT);
                    if (watts != null) {
                        period.power = watts.intValue();
                        logger.debug("Period {} power: {} W", periodIndex + 1, period.power);
                    }
                } else if (command instanceof DecimalType) {
                    period.power = ((DecimalType) command).intValue();
                    logger.debug("Period {} power: {} W", periodIndex + 1, period.power);
                }
                break;
        }
    }

    /**
     * Convert weekdays string to bitmask
     * Format: "Mon,Tue,Wed,Thu,Fri" or "Mon-Fri" or "Daily"
     * Bitmask: Mon=1, Tue=2, Wed=4, Thu=8, Fri=16, Sat=32, Sun=64
     */
    private int weekdaysStringToBitmask(String weekdays) {
        if ("Daily".equals(weekdays)) {
            return 127; // All days
        }

        int bitmask = 0;
        String[] days = weekdays.split(",");

        for (String day : days) {
            day = day.trim();
            switch (day) {
                case "Mon":
                    bitmask |= 1;
                    break;
                case "Tue":
                    bitmask |= 2;
                    break;
                case "Wed":
                    bitmask |= 4;
                    break;
                case "Thu":
                    bitmask |= 8;
                    break;
                case "Fri":
                    bitmask |= 16;
                    break;
                case "Sat":
                    bitmask |= 32;
                    break;
                case "Sun":
                    bitmask |= 64;
                    break;
                case "Mon-Fri":
                    bitmask = 31; // Mon + Tue + Wed + Thu + Fri
                    break;
                case "Weekend":
                    bitmask |= 96; // Sat + Sun
                    break;
            }
        }

        return bitmask;
    }

    /**
     * Activate manual mode with configured time periods
     */
    private void activateManualMode() {
        if (disposed) {
            return;
        }

        try {
            String host = config != null ? config.hostname : "127.0.0.1";
            int port = config != null ? config.port : 30000;

            // Send ES.SetMode for each enabled period
            int successCount = 0;
            int enabledCount = 0;

            for (int i = 0; i < timePeriods.length; i++) {
                if (timePeriods[i].enabled) {
                    enabledCount++;
                    TimePeriod period = timePeriods[i];

                    // Build request for this period (0-based index: 0=timer1, 1=timer2, etc)
                    String request = createRequest("ES.SetMode", String.format(
                            "\"config\":{\"mode\":\"Manual\",\"manual_cfg\":{\"time_num\":%d,\"start_time\":\"%s\",\"end_time\":\"%s\",\"week_set\":%d,\"power\":%d,\"enable\":1}}",
                            i, period.start, period.end, period.weekdaysBitmask, period.power));

                    logger.debug("Configuring manual mode period {} with request: {}", i, request);
                    logger.info("Manual mode: period {}, time {}-{}, weekdays {}, power {}W", i, period.start,
                            period.end, period.weekdaysBitmask, period.power);

                    String response = sendRequest(request);

                    if (response != null) {
                        logger.debug("Period {} response: {}", i, response);

                        JsonObject json = gson.fromJson(response, JsonObject.class);
                        JsonObject result = json != null ? json.getAsJsonObject("result") : null;

                        if (result != null && result.has("set_result") && result.get("set_result").getAsBoolean()) {
                            successCount++;
                        } else {
                            logger.warn("Failed to configure period {}", i);
                        }
                    } else {
                        logger.warn("No response when configuring period {}", i);
                    }

                    // Delay between requests to avoid overwhelming device
                    if (i < timePeriods.length - 1 && timePeriods[i + 1].enabled) {
                        Thread.sleep(300);
                    }
                }
            }

            if (enabledCount == 0) {
                logger.warn("No enabled time periods found for manual mode");
                updateState(CHANNEL_MANUAL_ACTIVATE, OnOffType.OFF);
                return;
            }

            if (successCount == enabledCount) {
                logger.info("Successfully activated manual mode with {} periods", successCount);
                // Reset the switch and update mode after a short delay
                scheduler.schedule(() -> {
                    updateState(CHANNEL_MANUAL_ACTIVATE, OnOffType.OFF);
                    refresh();
                }, 1, java.util.concurrent.TimeUnit.SECONDS);
            } else {
                logger.warn("Activated manual mode but only {} of {} periods succeeded", successCount, enabledCount);
                updateState(CHANNEL_MANUAL_ACTIVATE, OnOffType.OFF);
            }
        } catch (Exception e) {
            logger.warn("Error activating manual mode: {}", e.getMessage(), e);
            updateState(CHANNEL_MANUAL_ACTIVATE, OnOffType.OFF);
        }
    }
}

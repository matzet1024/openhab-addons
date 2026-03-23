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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The {@link marstekConfiguration} class contains fields mapping thing configuration parameters.
 *
 * @author acfischer42 - Initial contribution
 */
@NonNullByDefault
public class marstekConfiguration {

    /**
     * Sample configuration parameters. Replace with your own.
     */
    public String hostname = "";
    public String password = "";
    public int refreshInterval = 600;
    /**
     * UDP port of the Marstek device. If 0 the handler will use the default port 30000.
     */
    public int port = 30000;
    public int localPort = 30000;
}

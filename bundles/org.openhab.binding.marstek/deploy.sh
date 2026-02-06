#!/bin/bash

# Deploy the Marstek binding JAR to openHAB on majestix server

REMOTE_HOST="majestix"
REMOTE_PATH="/srv/containers/home-automation/openhab/v5/addons/"
JAR_FILE="target/org.openhab.binding.marstek-5.2.0-SNAPSHOT.jar"

# Check if JAR exists
if [ ! -f "$JAR_FILE" ]; then
    echo "Error: JAR file not found at $JAR_FILE"
    echo "Run 'mvn clean install' first to build the binding"
    exit 1
fi

# Copy to remote openHAB addons directory
echo "Deploying $JAR_FILE to $REMOTE_HOST:$REMOTE_PATH..."
scp "$JAR_FILE" "$REMOTE_HOST:$REMOTE_PATH"

if [ $? -eq 0 ]; then
    echo "✓ Deployment successful!"
    echo "The binding will be automatically loaded by openHAB"
    echo ""
    echo "To view logs on majestix, run:"
    echo "  ssh majestix 'tail -f /var/log/openhab/openhab.log' | grep -i marstek"
    echo ""
    echo "To enable TRACE logging for detailed diagnostics:"
    echo "  ssh majestix"
    echo "  Then run: openhab-cli console"
    echo "  In console: log:set TRACE org.openhab.binding.marstek"
else
    echo "✗ Deployment failed!"
    exit 1
fi

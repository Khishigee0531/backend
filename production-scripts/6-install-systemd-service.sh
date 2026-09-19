#!/bin/bash
# Install systemd service for auto-restart

echo "📦 Installing systemd service..."

# Stop current backend
./1-stop-backend.sh

# Copy service file
cp 5-backend.service /etc/systemd/system/poker-backend.service

# Reload systemd
systemctl daemon-reload

# Enable service (auto-start on boot)
systemctl enable poker-backend.service

# Start service
systemctl start poker-backend.service

echo "✅ Systemd service installed!"
echo ""
echo "📋 Useful commands:"
echo "   systemctl status poker-backend    - Check status"
echo "   systemctl restart poker-backend   - Restart"
echo "   systemctl stop poker-backend      - Stop"
echo "   systemctl start poker-backend     - Start"
echo "   journalctl -u poker-backend -f    - View logs"

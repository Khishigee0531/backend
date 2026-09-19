#!/bin/bash
# Restart backend script

echo "🔄 Restarting backend..."

# Stop
./1-stop-backend.sh

# Wait a moment
sleep 2

# Start
./2-start-backend-optimized.sh

echo "✅ Backend restarted successfully!"

#!/bin/bash
# Script to stop the running backend

echo "🛑 Stopping backend..."
pkill -f app.jar
sleep 2
echo "✅ Backend stopped"

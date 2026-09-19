#!/bin/bash
# Check backend status

echo "=========================================="
echo "📊 Backend Status Check"
echo "=========================================="
echo ""

if [ -f /opt/backend/app.pid ]; then
    PID=$(cat /opt/backend/app.pid)
    if ps -p $PID > /dev/null 2>&1; then
        echo "✅ Backend is RUNNING (PID: $PID)"
        echo ""
        echo "📊 Process Info:"
        ps aux | grep $PID | grep -v grep
        echo ""
        echo "💾 Memory Usage:"
        ps -p $PID -o %mem,rss,vsz
    else
        echo "❌ Backend is NOT running (PID file exists but process is dead)"
    fi
else
    echo "❌ Backend is NOT running (no PID file)"
fi

echo ""
echo "🌐 Port Check (8080):"
netstat -tuln | grep 8080 || echo "Port 8080 is not in use"

echo ""
echo "📝 Recent Logs (last 20 lines):"
if [ -f /opt/backend/logs/app.log ]; then
    tail -n 20 /opt/backend/logs/app.log
else
    echo "No logs found"
fi

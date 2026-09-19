#!/bin/bash

echo "🔍 SERVER HEALTH CHECK"
echo "======================"
echo ""

echo "📊 System Resources:"
echo "-------------------"
echo "CPU Cores: $(nproc)"
echo "CPU Load: $(uptime | awk -F'load average:' '{print $2}')"
echo ""

echo "💾 Memory:"
echo "---------"
free -h
echo ""

echo "💿 Disk I/O:"
echo "-----------"
iostat -x 1 2 | tail -n +4 || echo "iostat not available (install sysstat)"
echo ""

echo "🌐 Network:"
echo "----------"
ss -s || netstat -s 2>/dev/null | head -20
echo ""

echo "☕ Java Processes:"
echo "----------------"
ps aux | grep java | grep -v grep
echo ""

echo "🗄️ Database Connections:"
echo "----------------------"
echo "PostgreSQL connections:"
psql -U poker -d poker -c "SELECT count(*) as total_connections, state FROM pg_stat_activity GROUP BY state;" 2>/dev/null || echo "Cannot connect to PostgreSQL"
echo ""

echo "🔗 Active Network Connections:"
echo "-----------------------------"
netstat -an | grep -E ':8080|:5432|:6379' | wc -l
echo ""

echo "🎯 Recommendations:"
echo "------------------"
CORES=$(nproc)
TOTAL_MEM=$(free -m | awk '/^Mem:/{print $2}')
JAVA_PROCS=$(ps aux | grep java | grep -v grep | wc -l)

if [ $CORES -lt 4 ]; then
    echo "⚠️  LOW CPU: Only $CORES cores. Consider upgrading to 4+ cores."
fi

if [ $TOTAL_MEM -lt 2048 ]; then
    echo "⚠️  LOW RAM: Only ${TOTAL_MEM}MB. Recommend 4GB+ for production."
fi

if [ $JAVA_PROCS -gt 1 ]; then
    echo "⚠️  Multiple Java processes running. Consider consolidating."
fi

echo ""
echo "✅ Check complete!"

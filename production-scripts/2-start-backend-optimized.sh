#!/bin/bash
# Production startup – tuned for DigitalOcean 4 GB / 2 vCPU (Singapore)

echo "🚀 Starting backend..."
mkdir -p /opt/backend/logs

CPU_CORES=$(nproc)
IO_THREADS=$((CPU_CORES * 2))
WORKER_THREADS=$((CPU_CORES * 4))

nohup java \
  -server \
  -Xms1500m \
  -Xmx2800m \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:G1HeapRegionSize=16m \
  -XX:+UseStringDeduplication \
  -XX:+OptimizeStringConcat \
  -XX:+TieredCompilation \
  -Djava.net.preferIPv4Stack=true \
  -Duser.timezone=Asia/Ulaanbaatar \
  -Dquarkus.http.io-threads=$IO_THREADS \
  -Dquarkus.vertx.worker-pool-size=$WORKER_THREADS \
  -Dquarkus.log.level=WARN \
  -Dquarkus.log.category.\"dev.manestack\".level=INFO \
  -jar /opt/backend/app.jar \
  >> /opt/backend/logs/app.log 2>&1 &

echo $! > /opt/backend/app.pid
sleep 3
echo "✅ PID $(cat /opt/backend/app.pid)  io-threads=$IO_THREADS  worker-pool=$WORKER_THREADS"
echo "📝 tail -f /opt/backend/logs/app.log"

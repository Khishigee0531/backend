#!/bin/bash
# Complete deployment script - run from LOCAL machine

set -e

SERVER="root@143.110.135.172"
SSH_KEY="~/.ssh/id_poker"

echo "=========================================="
echo "🚀 Deploying to Production"
echo "=========================================="
echo ""

# Step 1: Build
echo "🔨 Step 1: Building backend..."
cd /home/hishig/Desktop/Gamble/boldooBack
./mvnw clean package -Dquarkus.package.type=uber-jar -DskipTests

if [ $? -ne 0 ]; then
    echo "❌ Build failed!"
    exit 1
fi

echo "✅ Build successful!"
echo ""

# Step 2: Deploy JAR
echo "📦 Step 2: Uploading JAR to server..."
scp -i $SSH_KEY target/poker-engine-1.0.0-SNAPSHOT-runner.jar $SERVER:/opt/backend/app.jar

if [ $? -ne 0 ]; then
    echo "❌ Upload failed!"
    exit 1
fi

echo "✅ Upload successful!"
echo ""

# Step 3: Restart backend
echo "🔄 Step 3: Restarting backend on server..."
ssh -i $SSH_KEY $SERVER << 'ENDSSH'
cd /opt/backend

# Stop old process
pkill -f app.jar
sleep 2

# Start with optimized settings
nohup java \
  -Xms1G \
  -Xmx2G \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=100 \
  -XX:+UseStringDeduplication \
  -Dquarkus.http.io-threads=4 \
  -Dquarkus.vertx.worker-pool-size=20 \
  -jar /opt/backend/app.jar \
  > /opt/backend/logs/app.log 2>&1 &

echo $! > /opt/backend/app.pid
sleep 3

echo "✅ Backend restarted with PID: $(cat /opt/backend/app.pid)"
ENDSSH

echo ""
echo "=========================================="
echo "✅ Deployment Complete!"
echo "=========================================="
echo ""
echo "📋 Next steps:"
echo "   1. Test the application"
echo "   2. Check logs: ssh $SERVER 'tail -f /opt/backend/logs/app.log'"
echo "   3. Check status: ssh $SERVER 'ps aux | grep app.jar'"

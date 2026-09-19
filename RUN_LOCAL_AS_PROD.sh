#!/bin/bash

echo "🚀 Building backend in production mode..."
./mvnw clean package -DskipTests

if [ $? -ne 0 ]; then
    echo "❌ Build failed!"
    exit 1
fi

echo ""
echo "✅ Build successful!"
echo ""
echo "🔧 Starting backend with production profile and verbose logging..."
echo ""

# Find the runner jar
RUNNER_JAR=$(find target -name "*-runner.jar" | head -1)

if [ -z "$RUNNER_JAR" ]; then
    echo "❌ No runner jar found!"
    exit 1
fi

echo "📦 Found jar: $RUNNER_JAR"
echo ""
echo "📊 Starting with these settings:"
echo "   - Database: PostgreSQL on localhost:5432"
echo "   - Redis: localhost:6379"
echo "   - Thread pools: GAMEPLAY=CPU*4, RESPONSE=CPU*8"
echo "   - Logging: INFO level with performance markers"
echo ""
echo "🔍 To see performance logs, open another terminal and run:"
echo "   tail -f target/quarkus.log | grep -E '🎮|📥|📤|✅'"
echo ""
echo "Press Ctrl+C to stop the server"
echo "================================================"
echo ""

# Run with production profile and performance logging
java -Dquarkus.log.console.enable=true \
     -Dquarkus.log.file.enable=true \
     -Dquarkus.log.file.path=target/quarkus.log \
     -Dquarkus.log.level=INFO \
     -Dquarkus.log.category."dev.manestack".level=INFO \
     -Xmx2G \
     -Xms1G \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=100 \
     -jar "$RUNNER_JAR"

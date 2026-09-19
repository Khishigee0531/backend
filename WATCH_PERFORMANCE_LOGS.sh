#!/bin/bash

echo "📊 Watching performance logs..."
echo "Take a poker action in your frontend to see the timing!"
echo "================================================"
echo ""

tail -f target/quarkus.log 2>/dev/null | grep --line-buffered -E '🎮|📥|📤|✅|GAME ACTION|ACTION START|ACTION COMPLETE|RESPONSE SENT|ACTION PROCESSED' || tail -f logs/application.log 2>/dev/null | grep --line-buffered -E '🎮|📥|📤|✅'

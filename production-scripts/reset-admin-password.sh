#!/bin/bash
# Reset admin password
# Usage: ./reset-admin-password.sh [new_password]

NEW_PASSWORD="${1:-123}"
DB_USER="${DB_USERNAME:-poker}"
DB_PASSWORD="${DB_PASSWORD:-poker321}"
DB_NAME="${DB_NAME:-poker}"

echo "Resetting admin password..."

# Generate BCrypt hash
HASH=$(java -cp /home/hishig/Desktop/backend/target/quarkus-app/app.jar:/home/hishig/Desktop/backend/target/quarkus-app/lib/main/*.jar \
  -Djava.util.concurrent.ForkJoinPool.common.parallelism=0 \
  io.quarkus.runner.GeneratedMain 2>/dev/null || \
  python3 -c "
import bcrypt
password = '$NEW_PASSWORD'.encode('utf-8')
salt = bcrypt.gensalt(rounds=12)
hashed = bcrypt.hashpw(password, salt)
print(hashed.decode('utf-8'))
" 2>/dev/null || \
  echo "FAILED")

if [ "$HASH" = "FAILED" ]; then
  echo "Failed to generate BCrypt hash. Installing bcrypt..."
  pip3 install bcrypt -q
  HASH=$(python3 -c "
import bcrypt
password = '$NEW_PASSWORD'.encode('utf-8')
salt = bcrypt.gensalt(rounds=12)
hashed = bcrypt.hashpw(password, salt)
print(hashed.decode('utf-8'))
")
fi

echo "Generated hash: ${HASH:0:20}..."

# Update password in database
PGPASSWORD="$DB_PASSWORD" psql -h 127.0.0.1 -p 5432 -U "$DB_USER" -d "$DB_NAME" -c "
UPDATE poker_user SET password = '$HASH' WHERE LOWER(username) = 'admin';
"

if [ $? -eq 0 ]; then
  echo "Admin password reset successfully!"
  echo "Username: admin"
  echo "Password: $NEW_PASSWORD"
else
  echo "Failed to update password in database"
fi

#!/bin/bash
# Automated PostgreSQL backup script
# Usage: ./backup-db.sh [backup_dir]

set -e

# Configuration
BACKUP_DIR="${1:-/opt/backups/postgres}"
RETENTION_DAYS=7
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="poker_backup_${TIMESTAMP}.sql.gz"

# Database connection (from environment or defaults)
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-poker}"
DB_USER="${DB_USERNAME:-poker}"
DB_PASSWORD="${DB_PASSWORD:-poker321}"

# Create backup directory if it doesn't exist
mkdir -p "${BACKUP_DIR}"

echo "Starting backup of ${DB_NAME}..."

# Create compressed backup
PGPASSWORD="${DB_PASSWORD}" pg_dump \
    -h "${DB_HOST}" \
    -p "${DB_PORT}" \
    -U "${DB_USER}" \
    -d "${DB_NAME}" \
    --format=custom \
    --compress=9 \
    -f "${BACKUP_DIR}/${BACKUP_FILE}"

echo "Backup created: ${BACKUP_DIR}/${BACKUP_FILE}"

# Calculate backup size
BACKUP_SIZE=$(du -h "${BACKUP_DIR}/${BACKUP_FILE}" | cut -f1)
echo "Backup size: ${BACKUP_SIZE}"

# Remove backups older than retention period
echo "Cleaning up backups older than ${RETENTION_DAYS} days..."
find "${BACKUP_DIR}" -name "poker_backup_*.sql.gz" -mtime +${RETENTION_DAYS} -delete

# Count remaining backups
BACKUP_COUNT=$(find "${BACKUP_DIR}" -name "poker_backup_*.sql.gz" | wc -l)
echo "Total backups: ${BACKUP_COUNT}"

echo "Backup completed successfully!"

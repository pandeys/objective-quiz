#!/usr/bin/env bash
# Nightly (and post-quiz) PostgreSQL backup, kept for 30 days.
set -euo pipefail
DIR=/var/backups/sitare-quiz
STAMP=$(date +%Y%m%d-%H%M)
sudo -u postgres pg_dump -Fc sitare_quiz > "$DIR/sitare_quiz-$STAMP.dump"
ln -sf "$DIR/sitare_quiz-$STAMP.dump" "$DIR/latest.dump"
find "$DIR" -name 'sitare_quiz-*.dump' -mtime +30 -delete

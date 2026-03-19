#!/bin/zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

echo "[1/7] Set flag to OLD"
perl -0pi -e 's/"defaultVariant": "old"|"defaultVariant": "dual"|"defaultVariant": "new"/"defaultVariant": "old"/g' flags.flagd.json
sleep 2
curl -s http://127.0.0.1:8080/api/migration/mode
echo

echo "[2/7] Reset new DB"
docker compose -f compose.yaml exec -T new-db mysql -uapp -papp -D community_new -e "SET FOREIGN_KEY_CHECKS=0; DELETE FROM post_comments; DELETE FROM posts; DELETE FROM boards; DELETE FROM members; SET FOREIGN_KEY_CHECKS=1; ALTER TABLE members AUTO_INCREMENT = 601; ALTER TABLE boards AUTO_INCREMENT = 601; ALTER TABLE posts AUTO_INCREMENT = 1201; ALTER TABLE post_comments AUTO_INCREMENT = 2401;"

echo "[3/7] Create 3 comments in OLD"
for i in 1 2 3; do
  curl -s -X POST http://127.0.0.1:8080/api/comments \
    -H 'Content-Type: application/json' \
    -d "{\"body\":\"phase-old-$i\",\"authorId\":42,\"postId\":42}"
  echo
done

echo "[4/7] Set flag to DUAL_WRITE"
perl -0pi -e 's/"defaultVariant": "old"|"defaultVariant": "dual"|"defaultVariant": "new"/"defaultVariant": "dual"/g' flags.flagd.json
sleep 2
curl -s http://127.0.0.1:8080/api/migration/mode
echo

echo "[5/7] Create 3 comments in DUAL_WRITE"
for i in 1 2 3; do
  curl -s -X POST http://127.0.0.1:8080/api/comments \
    -H 'Content-Type: application/json' \
    -d "{\"body\":\"phase-dual-$i\",\"authorId\":42,\"postId\":42}"
  echo
done

echo "[6/7] Set flag to NEW"
perl -0pi -e 's/"defaultVariant": "old"|"defaultVariant": "dual"|"defaultVariant": "new"/"defaultVariant": "new"/g' flags.flagd.json
sleep 2
curl -s http://127.0.0.1:8080/api/migration/mode
echo

echo "[7/7] Create 3 comments in NEW"
for i in 1 2 3; do
  curl -s -X POST http://127.0.0.1:8080/api/comments \
    -H 'Content-Type: application/json' \
    -d "{\"body\":\"phase-new-$i\",\"authorId\":42,\"postId\":42}"
  echo
done

echo
echo "old-primary"
docker compose -f compose.yaml exec -T old-primary mysql -uapp -papp -D community_old_primary -Nse \
  "select id, body from post_comments where body like 'phase-%' order by id;"

echo
echo "old-secondary"
docker compose -f compose.yaml exec -T old-secondary mysql -uapp -papp -D community_old_primary -Nse \
  "select id, body from post_comments where body like 'phase-%' order by id;"

echo
echo "new-db"
docker compose -f compose.yaml exec -T new-db mysql -uapp -papp -D community_new -Nse \
  "select id, body from post_comments where body like 'phase-%' order by id;"

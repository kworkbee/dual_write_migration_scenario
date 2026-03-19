#!/bin/bash
set -euo pipefail

docker-entrypoint.sh mysqld \
  --server-id=2 \
  --log-bin=mysql-bin \
  --binlog-format=ROW \
  --gtid-mode=ON \
  --enforce-gtid-consistency=ON \
  --relay-log=mysql-relay-bin \
  --skip-replica-start=1 &

mysql_pid=$!

until mysqladmin ping -h127.0.0.1 -uroot -proot --silent; do
  sleep 2
done

until mysqladmin ping -hold-primary -uroot -proot --silent; do
  sleep 2
done

mysql -uroot -proot <<'SQL'
STOP REPLICA;
RESET REPLICA ALL;
CHANGE REPLICATION SOURCE TO
  SOURCE_HOST='old-primary',
  SOURCE_PORT=3306,
  SOURCE_USER='replica',
  SOURCE_PASSWORD='replica',
  SOURCE_AUTO_POSITION=1,
  GET_SOURCE_PUBLIC_KEY=1;
START REPLICA;
SET GLOBAL read_only = ON;
SET GLOBAL super_read_only = ON;
SQL

wait "$mysql_pid"

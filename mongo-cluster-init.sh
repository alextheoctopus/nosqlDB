#!/usr/bin/env bash
set -euo pipefail

echo "=== Mongo cluster init started ==="

wait_for_mongo() {
  local host="$1"
  local port="$2"

  echo "Waiting for Mongo at $host:$port ..."
  until mongosh --quiet --host "$host" --port "$port" --eval 'db.adminCommand({ ping: 1 }).ok' >/dev/null 2>&1; do
    sleep 2
  done
  echo "Mongo is reachable: $host:$port"
}

wait_for_rs_primary() {
  local rs_name="$1"
  shift
  local timeout=180
  local elapsed=0

  echo "Waiting for PRIMARY in replica set $rs_name ..."

  while [ "$elapsed" -lt "$timeout" ]; do
    for target in "$@"; do
      local host="${target%:*}"
      local port="${target#*:}"

      local primary
      primary="$(
        mongosh --quiet --host "$host" --port "$port" --eval '
          try {
            const status = rs.status();
            const primary = status.members.find(m => m.stateStr === "PRIMARY");
            print(primary ? primary.name : "");
          } catch (e) {
            print("");
          }
        ' 2>/dev/null || true
      )"

      if [ -n "$primary" ]; then
        echo "Replica set $rs_name PRIMARY is ready: $primary"
        return 0
      fi
    done

    sleep 2
    elapsed=$((elapsed + 2))
  done

  echo "Timeout waiting for PRIMARY in replica set $rs_name"
  for target in "$@"; do
    local host="${target%:*}"
    local port="${target#*:}"
    echo "--- rs.status() from $host:$port ---"
    mongosh --host "$host" --port "$port" --eval 'try { rs.status() } catch (e) { print(e) }' || true
  done
  exit 1
}

init_rs_if_needed() {
  local host="$1"
  local port="$2"
  local config="$3"

  echo "Checking replica set at $host:$port ..."
  local status
  status="$(mongosh --quiet --host "$host" --port "$port" --eval 'try { rs.status().ok } catch (e) { print("NOT_INIT") }' 2>/dev/null || true)"

  if echo "$status" | grep -q "NOT_INIT"; then
    echo "Initializing replica set at $host:$port"
    mongosh --quiet --host "$host" --port "$port" --eval "rs.initiate($config)"
  else
    echo "Replica set already initialized at $host:$port"
  fi
}

add_shard_if_needed() {
  local shard="$1"
  local shard_name
  shard_name="$(echo "$shard" | cut -d/ -f1)"

  local shards
  shards="$(mongosh --quiet --host mongos --port 27017 --eval 'try { sh.status().shards.map(s => s._id).join(",") } catch (e) { print("") }' 2>/dev/null || true)"

  if echo "$shards" | grep -q "$shard_name"; then
    echo "Shard already added: $shard"
  else
    echo "Adding shard: $shard"
    mongosh --quiet --host mongos --port 27017 --eval "sh.addShard(\"$shard\")"
  fi
}

enable_sharding_if_needed() {
  local dbname="$1"
  echo "Enabling sharding for database $dbname"
  mongosh --quiet --host mongos --port 27017 --eval "try { sh.enableSharding(\"$dbname\") } catch (e) { print(e) }"
}

shard_collection_if_needed() {
  local ns="$1"
  echo "Ensuring sharding for collection $ns"
  mongosh --quiet --host mongos --port 27017 --eval "try { sh.shardCollection(\"$ns\", { created_by: \"hashed\" }) } catch (e) { print(e) }"
}

create_app_user_if_needed() {
  if [ -z "${MONGODB_USER:-}" ] || [ -z "${MONGODB_PASSWORD:-}" ]; then
    echo "App user credentials are empty, skipping user creation"
    return
  fi

  echo "Ensuring app user exists in ${MONGODB_DATABASE}"
  mongosh --quiet --host mongos --port 27017 <<EOF
use ${MONGODB_DATABASE}
if (!db.getUser("${MONGODB_USER}")) {
  db.createUser({
    user: "${MONGODB_USER}",
    pwd: "${MONGODB_PASSWORD}",
    roles: [{ role: "readWrite", db: "${MONGODB_DATABASE}" }]
  })
}
EOF
}

for target in \
  configsvr1:27019 configsvr2:27020 configsvr3:27021 \
  shard1a:27101 shard1b:27102 shard1c:27103 \
  shard2a:27201 shard2b:27202 shard2c:27203
do
  wait_for_mongo "${target%:*}" "${target#*:}"
done

init_rs_if_needed configsvr1 27019 '{ _id: "cfgRS", configsvr: true, members: [ { _id: 0, host: "configsvr1:27019" }, { _id: 1, host: "configsvr2:27020" }, { _id: 2, host: "configsvr3:27021" } ] }'
init_rs_if_needed shard1a 27101 '{ _id: "shard1RS", members: [ { _id: 0, host: "shard1a:27101" }, { _id: 1, host: "shard1b:27102" }, { _id: 2, host: "shard1c:27103" } ] }'
init_rs_if_needed shard2a 27201 '{ _id: "shard2RS", members: [ { _id: 0, host: "shard2a:27201" }, { _id: 1, host: "shard2b:27202" }, { _id: 2, host: "shard2c:27203" } ] }'

wait_for_rs_primary cfgRS \
  configsvr1:27019 configsvr2:27020 configsvr3:27021

wait_for_rs_primary shard1RS \
  shard1a:27101 shard1b:27102 shard1c:27103

wait_for_rs_primary shard2RS \
  shard2a:27201 shard2b:27202 shard2c:27203

wait_for_mongo mongos 27017

add_shard_if_needed "shard1RS/shard1a:27101,shard1b:27102,shard1c:27103"
add_shard_if_needed "shard2RS/shard2a:27201,shard2b:27202,shard2c:27203"

enable_sharding_if_needed "${MONGODB_DATABASE}"
shard_collection_if_needed "${MONGODB_DATABASE}.events"

create_app_user_if_needed

echo "=== Mongo cluster init completed successfully ==="
exit 0
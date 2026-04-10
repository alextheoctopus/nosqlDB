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

wait_for_primary() {
  local host="$1"
  local port="$2"
  local timeout=120
  local elapsed=0

  echo "Waiting for PRIMARY at $host:$port ..."
  until [ "$(mongosh --quiet --host "$host" --port "$port" --eval 'db.hello().isWritablePrimary ? "1" : "0"' 2>/dev/null || echo 0)" = "1" ]; do
    sleep 2
    elapsed=$((elapsed + 2))
    if [ "$elapsed" -ge "$timeout" ]; then
      echo "Timeout waiting for PRIMARY at $host:$port"
      mongosh --host "$host" --port "$port" --eval 'try { rs.status() } catch(e) { print(e) }' || true
      exit 1
    fi
  done
  echo "PRIMARY is ready: $host:$port"
}

init_rs_if_needed() {
  local host="$1"
  local port="$2"
  local config="$3"

  echo "Checking replica set at $host:$port ..."
  local status
  status="$(mongosh --quiet --host "$host" --port "$port" --eval 'try { rs.status().ok } catch(e) { print("NOT_INIT") }' 2>/dev/null || true)"

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
  shards="$(mongosh --quiet --host mongos --port 27017 --eval 'try { sh.status().shards.map(s => s._id).join(",") } catch(e) { print("") }' 2>/dev/null || true)"

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
  mongosh --quiet --host mongos --port 27017 --eval "try { sh.enableSharding(\"$dbname\") } catch(e) { print(e) }"
}

shard_collection_if_needed() {
  local ns="$1"
  echo "Ensuring sharding for collection $ns"
  mongosh --quiet --host mongos --port 27017 --eval "try { sh.shardCollection(\"$ns\", { created_by: \"hashed\" }) } catch(e) { print(e) }"
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

# Wait only for mongod nodes first, NOT mongos
for target in \
  configsvr1:27019 configsvr2:27020 configsvr3:27021 \
  shard1a:27101 shard1b:27102 shard1c:27103 \
  shard2a:27201 shard2b:27202 shard2c:27203
do
  wait_for_mongo "${target%:*}" "${target#*:}"
done

# Init replica sets
init_rs_if_needed configsvr1 27019 '{ _id: "cfgRS", configsvr: true, members: [ { _id: 0, host: "configsvr1:27019" }, { _id: 1, host: "configsvr2:27020" }, { _id: 2, host: "configsvr3:27021" } ] }'
init_rs_if_needed shard1a 27101 '{ _id: "shard1RS", members: [ { _id: 0, host: "shard1a:27101" }, { _id: 1, host: "shard1b:27102" }, { _id: 2, host: "shard1c:27103" } ] }'
init_rs_if_needed shard2a 27201 '{ _id: "shard2RS", members: [ { _id: 0, host: "shard2a:27201" }, { _id: 1, host: "shard2b:27202" }, { _id: 2, host: "shard2c:27203" } ] }'

sleep 10

# Wait for elected primaries
wait_for_primary configsvr1 27019
wait_for_primary shard1a 27101
wait_for_primary shard2a 27201

# ONLY NOW wait for mongos
wait_for_mongo mongos 27017

# Add shards and enable sharding
add_shard_if_needed "shard1RS/shard1a:27101,shard1b:27102,shard1c:27103"
add_shard_if_needed "shard2RS/shard2a:27201,shard2b:27202,shard2c:27203"
enable_sharding_if_needed "${MONGODB_DATABASE}"
shard_collection_if_needed "${MONGODB_DATABASE}.events"

create_app_user_if_needed

echo "=== Mongo cluster init completed successfully ==="
exit 0
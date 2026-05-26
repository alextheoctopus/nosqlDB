#!/usr/bin/env bash
set -e

echo "Waiting for Cassandra..."

until cqlsh cassandra-dc1-node1 9042 -e "DESCRIBE KEYSPACES"; do
  echo "Cassandra is not ready yet..."
  sleep 2
done

echo "Initializing Cassandra schema..."

cqlsh cassandra-dc1-node1 9042 <<EOF
CREATE KEYSPACE IF NOT EXISTS ${CASSANDRA_KEYSPACE}
WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

USE ${CASSANDRA_KEYSPACE};

CREATE TABLE IF NOT EXISTS event_reactions (
    event_id text,
    created_by text,
    like_value tinyint,
    created_at timestamp,
    PRIMARY KEY ((event_id), created_by)
);

CREATE INDEX IF NOT EXISTS event_reactions_like_value_idx
ON event_reactions (like_value);

CREATE INDEX IF NOT EXISTS event_reactions_created_by_idx
ON event_reactions (created_by);

CREATE TABLE IF NOT EXISTS event_reviews (
    event_id text,
    created_by text,
    id uuid,
    rating tinyint,
    comment text,
    created_at timestamp,
    updated_at timestamp,
    PRIMARY KEY ((event_id), created_by)
);

CREATE INDEX IF NOT EXISTS event_reviews_id_idx
ON event_reviews (id);
EOF

echo "Cassandra schema initialized"
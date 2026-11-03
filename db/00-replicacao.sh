#!/bin/bash
# Corre no MESTRE na primeira inicializacao: cria o utilizador de replicacao
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -c "CREATE ROLE replicador WITH REPLICATION LOGIN PASSWORD 'replica';"
# Autoriza ligacoes de replicacao vindas da rede Docker
echo "host replication replicador all scram-sha-256" >> "$PGDATA/pg_hba.conf"
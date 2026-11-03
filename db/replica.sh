#!/bin/bash
# Corre na REPLICA: copia o mestre (pg_basebackup) e arranca como standby
set -e
echo "A aguardar o mestre $PRIMARIO..."
until pg_isready -h "$PRIMARIO" -p 5432 -U postgres; do sleep 2; done

if [ ! -s "$PGDATA/PG_VERSION" ]; then
  echo "Primeira execucao: copia base do mestre $PRIMARIO"
  PGPASSWORD="$REPL_PASS" pg_basebackup -h "$PRIMARIO" -U replicador \
    -D "$PGDATA" -X stream -R -P
  chmod 700 "$PGDATA"
fi

exec postgres -c max_prepared_transactions=10 -c cluster_name="$NOME_NO"
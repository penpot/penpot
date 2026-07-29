#!/usr/bin/env bash

set -x

export OLDVER=${1:-13}
export NEWVER=$(pg_ctl --version | sed -nE 's/^.+ .+ ([0-9]+).*$/\1/p');

export PGBINOLD=/usr/lib/postgresql/${OLDVER}/bin
export PGBINNEW=/usr/lib/postgresql/${NEWVER}/bin
export PGDATAOLD=/var/lib/postgresql/${OLDVER}/data
export PGDATANEW=/var/lib/postgresql/${NEWVER}/data

sed -i "s/$/ ${OLDVER}/" /etc/apt/sources.list.d/pgdg.list

apt-get update \
  && apt-get install -y --no-install-recommends postgresql-${OLDVER} \
  && rm -rf /var/lib/apt/lists/*

mkdir -p "$PGDATAOLD" "$PGDATANEW" \
  && chown -R postgres:postgres /var/lib/postgresql

pushd /var/lib/postgresql

PGDATA=$PGDATANEW gosu postgres initdb -U penpot --data-checksums
gosu postgres pg_upgrade -U penpot

cp $PGDATAOLD/pg_hba.conf $PGDATANEW/pg_hba.conf

popd

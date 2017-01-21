#!/usr/bin/env bash

DIR=`dirname $0`
LEIN="$DIR/lein"

LEIN_SNAPSHOTS_IN_RELEASE=1 $LEIN uberjar

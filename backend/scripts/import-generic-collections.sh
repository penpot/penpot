#!/usr/bin/env bash

DIR=`dirname $0`
LEIN="$DIR/lein"

$LEIN trampoline run -m uxbox.cli.collimp/-main -- $@


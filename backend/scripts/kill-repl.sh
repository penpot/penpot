#!/usr/bin/env bash
set -x

jcmd |grep "rebel" |sed -nE 's/^([0-9]+).*$/\1/p' | xargs kill -9

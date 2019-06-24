#!/usr/bin/env bash

set -x

sudo pg_ctlcluster 9.6 main start
clj -Adev -m uxbox.tests.main

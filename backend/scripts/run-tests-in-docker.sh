#!/usr/bin/env bash

set -xe
sudo pg_ctlcluster 11 main start;
clojure -Adev -m uxbox.tests.main;

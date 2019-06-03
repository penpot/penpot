#!/usr/bin/env bash

source ~/.bashrc

echo `env`

cd /home/uxbox/uxbox/frontend
npm install || exit 1;
npm run dist
# TODO: WIP

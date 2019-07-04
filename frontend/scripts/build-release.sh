#!/usr/bin/env bash
source ~/.bashrc

npm ci

npm run dist:clean || exit 1;
npm run dist:assets || exit 1;
npm run dist:all || exit 1;
# npm run dist:main || exit 1;
# npm run dist:view || exit 1;
# npm run dist:worker || exit 1;

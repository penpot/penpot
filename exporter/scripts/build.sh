#!/usr/bin/env bash

source ~/.bashrc
set -ex

yarn install
rm -rf target

export NODE_ENV=production;

# Build the application
npx shadow-cljs release main

# Remove source
rm -rf target/app

# Copy package*.json files
cp package-lock.json target/
cp package.json target/

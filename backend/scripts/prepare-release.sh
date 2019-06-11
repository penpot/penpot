#!/usr/bin/env bash
rm -rf ../dist || exit 1;

rsync -avr \
        --exclude="/test" \
        --exclude="/resources/public/media" \
        --exclude="/target" \
        --exclude="/scripts" \
        --exclude="/.*" \
        ../ ../dist/;

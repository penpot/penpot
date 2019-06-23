#!/usr/bin/env bash
if [ ! -d "$1" ] || [ ! -d "$2" ]; then
        echo "Expecting path to backend and destination directory"
        exit 1
fi

rm -rf $2 || exit 1;

rsync -avr \
        --exclude="/test" \
        --exclude="/resources/public/media" \
        --exclude="/target" \
        --exclude="/scripts" \
        --exclude="/.*" \
        $1 $2;

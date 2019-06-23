#!/usr/bin/env bash

if [ "$#" -e 0 ]; then
        echo "Expecting parameters: 1=path to backend; 2=destination directory"
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

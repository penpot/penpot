#!/usr/bin/env sh

set -ex

SCALE_FACTOR=0.18
OUTPUT=output
for image in $1/*
do
./quantize.sh $image
INPUT_BASE=`basename $image .bmp`
#pamscale ${SCALE_FACTOR} -nomix tmp/${INPUT_BASE}-quant.ppm > tmp/${INPUT_BASE}-quant-scale_${SCALE_FACTOR}.ppm
./trace.sh tmp/${INPUT_BASE}-quant-scale_${SCALE_FACTOR}.ppm
gzip trace-output.svg
mv trace-output.svg.gz ${OUTPUT}/trace-${INPUT_BASE}-${SCALE_FACTOR}.svgz
done

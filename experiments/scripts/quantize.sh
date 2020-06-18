EDGE_THRESHOLD=0.1
INPUT=`basename $1 .bmp`
bmptoppm $1 > tmp/$INPUT.ppm
ppmtopgm tmp/$INPUT.ppm > tmp/$INPUT.pgm
pgmedge tmp/${INPUT}.pgm > tmp/$INPUT-edge.pgm
pgmtopbm -threshold -value ${EDGE_THRESHOLD} tmp/$INPUT-edge.pgm > tmp/${INPUT}-${EDGE_THRESHOLD}.pbm
pnmcomp -alpha tmp/${INPUT}-${EDGE_THRESHOLD}.pbm black_1600x800.ppm tmp/$INPUT.ppm > tmp/$INPUT-aliased.ppm
ppmquant -map palette.ppm tmp/$INPUT-aliased.ppm > tmp/$INPUT-quant.ppm


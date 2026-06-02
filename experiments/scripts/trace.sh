#!/usr/bin/env sh

set -ex

combine(){
pnmpaste -or tmp/cmask-invert-${1}.pbm 0 0 tmp/cmask-invert-${2}.pbm >  tmp/combine-tmp.pbm
mv tmp/combine-tmp.pbm tmp/cmask-invert-${2}.pbm
}

invert() {
pnminvert tmp/cmask-${1}.pbm > tmp/cmask-invert-${1}.pbm
}

invert2() {
pnminvert tmp/cmask-invert-${1}.pbm > tmp/cmask-${1}.pbm
}

mask() {
ppmcolormask "#${1}" $INPUT_FILE > tmp/cmask-${1}.pbm
}

trace() {
#potrace --svg --opttolerance 5 --alphamax 1.334 tmp/cmask000.pbm
potrace --svg --color "#${1}" tmp/cmask-${1}.pbm
}

svg_prepend(){
xsltproc --stringparam prepend tmp/cmask-${1}.svg combine.xslt trace-output.svg > tmp/combine-tmp.svg
mv tmp/combine-tmp.svg trace-output.svg
}

#COLORS=ffffff c5eeff ddd9d4 fdb696 c9463b 736026 af1a1a 3c3b39 6e0c00
COLORS="6a0909 302621 22323f af1a1a 43551e 60492d 3f515f 8f3131 7a543b 677a88 e17070 e99282 b3adad f6b18f ffd86f d5dfe1 f2f2f2 ffffff"
INPUT_FILE=$1

mask 000000
invert 000000

previous=

for color in $COLORS
do
  mask ${color}
  trace ${color}
  invert ${color}  
  combine ${color} 000000
  if [ -n "$previous" ] 
  then
    svg_prepend ${color} 
  else 
    cp tmp/cmask-${color}.svg trace-output.svg
  fi
  previous=${color}
done

invert2 000000
trace 000000
svg_prepend 000000

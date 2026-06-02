#!/bin/bash
echo -e "\x1B[0;41mmf/use-fn\x1B[0m\n"

#
# Get count of expressions
#
FN_COUNT=$(egrep -rn ":on-.*?\s+\(fn" src/app/main/ui | wc -l)
PARTIAL_COUNT=$(egrep -rn ":on-.*?\s+\(partial" src/app/main/ui | wc -l)
AFN_COUNT=$(egrep -rn ":on-.*?\s+#\(" src/app/main/ui | wc -l)

#
# Show counts
#
echo -e ":on-.*? (fn \x1B[0;31m" $FN_COUNT "\x1B[0m"
echo -e ":on-.*? (partial \x1B[0;31m" $PARTIAL_COUNT "\x1B[0m"
echo -e ":on-.*? #(\x1B[0;31m" $AFN_COUNT "\x1B[0m\n"

echo -e "total: \x1B[0;31m" $((FN_COUNT + PARTIAL_COUNT + AFN_COUNT)) "\x1B[0m\n"

# Show summary or show file list
if [[ $1 == "-s" ]]; then
  #
  # Files with handlers that don't use mf/use-fn
  #
  egrep -rn ":on-.*?\s+#?\((fn|partial)" src/app/main/ui | egrep -o "src/app/.*?\.cljs:" | uniq
else
  #
  # List files with lines
  #
  egrep -rn ":on-.*?\s+#?\((fn|partial)" src/app/main/ui | egrep -o "src/app/.*?\.cljs:([0-9]+)"
fi

#!/usr/bin/env bash

for f in resources/emails-mjml/*/*.mjml
do
  npx mjml $f -o `echo $f | sed -e "s/-mjml//" | sed -e "s/mjml/html/"`
done


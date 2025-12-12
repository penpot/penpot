#!/bin/bash
set -x
BROTLI_TAG=${BROTLI_TAG:-dev/null}
BROTLI_TAG="${BROTLI_TAG//'/'/%2F}" # Escaping for tag names with slash (e.g. "dev/null")
ARCHIVE=testdata.txz
curl -L https://github.com/google/brotli/releases/download/${BROTLI_TAG}/${ARCHIVE} -o ${ARCHIVE}
tar xvfJ ${ARCHIVE}

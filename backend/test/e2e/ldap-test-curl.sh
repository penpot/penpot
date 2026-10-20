#!/bin/bash
# LDAP injection test script — run against running Penpot backend
# Usage: bash backend/test/e2e/ldap-test-curl.sh

BASE="http://localhost:3450/api/main/methods/login-with-ldap"
H1='Content-Type: application/json'
H2='Accept: application/json'

#echo "=== 1. Normal login (fry/fry) ==="
#curl -s -X POST "$BASE" -H "$H1" -H "$H2" \
#  -d '{"email":"fry@planetexpress.com","password":"fry"}' | python3 -m json.tool

#echo ""
#echo "=== 2. Wildcard injection (*@planetexpress.com + amy) ==="
#curl -s -X POST "$BASE" -H "$H1" -H "$H2" \
#  -d '{"email":"*@planetexpress.com","password":"amy"}' | python3 -m json.tool

echo ""
echo "=== 3. Identity swap (hubert@ + professor password) ==="
curl -s -X POST "$BASE" -H "$H1" -H "$H2" \
  -d '{"email":"hubert@planetexpress.com","password":"professor"}' | python3 -m json.tool

#echo ""
#echo "=== 4. Wrong password ==="
#curl -s -X POST "$BASE" -H "$H1" -H "$H2" \
#  -d '{"email":"fry@planetexpress.com","password":"wrong"}' | python3 -m json.tool

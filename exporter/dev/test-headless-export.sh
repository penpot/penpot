curl -k -o board.pdf "${BASE_URL}/api/export" \
  -X POST \
  -H 'Content-Type: application/transit+json' \
  -H "Authorization: Token ${TOKEN}" \
  --data-raw '{"~:wait":true,"~:external":true,"~:exports":[{"~:type":"~:pdf","~:suffix":"","~:scale":1,"~:page-id":"~u93264a3d-cc0b-8040-8007-dfb612f46337","~:file-id":"~u93264a3d-cc0b-8040-8007-dfb612f46336","~:name":"Board","~:object-id":"~u6e0d0813-6563-80ea-8007-dfb61448bb4b"}],"~:profile-id":"~u6bd7c17d-4f59-815e-8006-5c1f68817a3b","~:cmd":"~:export-shapes","~:is-wasm":true}'

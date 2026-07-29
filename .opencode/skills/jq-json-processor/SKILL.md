---
name: jq-json-processor
description: Process, filter, and transform JSON data using jq - the lightweight and flexible command-line JSON processor.
homepage: https://jqlang.github.io/jq/
metadata: {"clawdbot":{"emoji":"🔍","requires":{"bins":["jq"]},"install":[{"id":"brew","kind":"brew","formula":"jq","bins":["jq"],"label":"Install jq (brew)"},{"id":"apt","kind":"apt","package":"jq","bins":["jq"],"label":"Install jq (apt)"}]}}
---

# jq JSON Processor

Process, filter, and transform JSON data with jq.

## Quick Examples

### Basic filtering
```bash
# Extract a field
echo '{"name":"Alice","age":30}' | jq '.name'
# Output: "Alice"

# Multiple fields
echo '{"name":"Alice","age":30}' | jq '{name: .name, age: .age}'

# Array indexing
echo '[1,2,3,4,5]' | jq '.[2]'
# Output: 3
```

### Working with arrays
```bash
# Map over array
echo '[{"name":"Alice"},{"name":"Bob"}]' | jq '.[].name'
# Output: "Alice" "Bob"

# Filter array
echo '[1,2,3,4,5]' | jq 'map(select(. > 2))'
# Output: [3,4,5]

# Length
echo '[1,2,3]' | jq 'length'
# Output: 3
```

### Common operations
```bash
# Pretty print JSON
cat file.json | jq '.'

# Compact output
cat file.json | jq -c '.'

# Raw output (no quotes)
echo '{"name":"Alice"}' | jq -r '.name'
# Output: Alice

# Sort keys
echo '{"z":1,"a":2}' | jq -S '.'
```

### Advanced filtering
```bash
# Select with conditions
jq '[.[] | select(.age > 25)]' people.json

# Group by
jq 'group_by(.category)' items.json

# Reduce
echo '[1,2,3,4,5]' | jq 'reduce .[] as $item (0; . + $item)'
# Output: 15
```

### Working with files
```bash
# Read from file
jq '.users[0].name' users.json

# Multiple files
jq -s '.[0] * .[1]' file1.json file2.json

# Modify and save
jq '.version = "2.0"' package.json > package.json.tmp && mv package.json.tmp package.json
```

## Common Use Cases

**Extract specific fields from API response:**
```bash
curl -s https://api.github.com/users/octocat | jq '{name: .name, repos: .public_repos, followers: .followers}'
```

**Convert CSV-like data:**
```bash
jq -r '.[] | [.name, .email, .age] | @csv' users.json
```

**Debug API responses:**
```bash
curl -s https://api.example.com/data | jq '.'
```

## Tips

- Use `-r` for raw string output (removes quotes)
- Use `-c` for compact output (single line)
- Use `-S` to sort object keys
- Use `--arg name value` to pass variables
- Pipe multiple jq operations: `jq '.a' | jq '.b'`

## Documentation

Full manual: https://jqlang.github.io/jq/manual/
Interactive tutorial: https://jqplay.org/

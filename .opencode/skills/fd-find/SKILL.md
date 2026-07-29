---
name: fd-find
description: A fast and user-friendly alternative to 'find' - simple syntax, smart defaults, respects gitignore.
homepage: https://github.com/sharkdp/fd
metadata: {"clawdbot":{"emoji":"📂","requires":{"bins":["fd"]},"install":[{"id":"brew","kind":"brew","formula":"fd","bins":["fd"],"label":"Install fd (brew)"},{"id":"apt","kind":"apt","package":"fd-find","bins":["fd"],"label":"Install fd (apt)"}]}}
---

# fd - Fast File Finder

User-friendly alternative to `find` with smart defaults.

## Quick Start

### Basic search
```bash
# Find files by name
fd pattern

# Find in specific directory
fd pattern /path/to/dir

# Case-insensitive
fd -i pattern
```

### Common patterns
```bash
# Find all Python files
fd -e py

# Find multiple extensions
fd -e py -e js -e ts

# Find directories only
fd -t d pattern

# Find files only
fd -t f pattern

# Find symlinks
fd -t l
```

## Advanced Usage

### Filtering
```bash
# Exclude patterns
fd pattern -E "node_modules" -E "*.min.js"

# Include hidden files
fd -H pattern

# Include ignored files (.gitignore)
fd -I pattern

# Search all (hidden + ignored)
fd -H -I pattern

# Maximum depth
fd pattern -d 3
```

### Execution
```bash
# Execute command on results
fd -e jpg -x convert {} {.}.png

# Parallel execution
fd -e md -x wc -l

# Use with xargs
fd -e log -0 | xargs -0 rm
```

### Regex patterns
```bash
# Full regex search
fd '^test.*\.js$'

# Match full path
fd --full-path 'src/.*/test'

# Glob pattern
fd -g "*.{js,ts}"
```

## Time-based filtering
```bash
# Modified within last day
fd --changed-within 1d

# Modified before specific date
fd --changed-before 2024-01-01

# Created recently
fd --changed-within 1h
```

## Size filtering
```bash
# Files larger than 10MB
fd --size +10m

# Files smaller than 1KB
fd --size -1k

# Specific size range
fd --size +100k --size -10m
```

## Output formatting
```bash
# Absolute paths
fd --absolute-path

# List format (like ls -l)
fd --list-details

# Null separator (for xargs)
fd -0 pattern

# Color always/never/auto
fd --color always pattern
```

## Common Use Cases

**Find and delete old files:**
```bash
fd --changed-before 30d -t f -x rm {}
```

**Find large files:**
```bash
fd --size +100m --list-details
```

**Copy all PDFs to directory:**
```bash
fd -e pdf -x cp {} /target/dir/
```

**Count lines in all Python files:**
```bash
fd -e py -x wc -l | awk '{sum+=$1} END {print sum}'
```

**Find broken symlinks:**
```bash
fd -t l -x test -e {} \; -print
```

**Search in specific time window:**
```bash
fd --changed-within 2d --changed-before 1d
```

## Integration with other tools

**With ripgrep:**
```bash
fd -e js | xargs rg "pattern"
```

**With fzf (fuzzy finder):**
```bash
vim $(fd -t f | fzf)
```

**With bat (cat alternative):**
```bash
fd -e md | xargs bat
```

## Performance Tips

- `fd` is typically much faster than `find`
- Respects `.gitignore` by default (disable with `-I`)
- Uses parallel traversal automatically
- Smart case: lowercase = case-insensitive, any uppercase = case-sensitive

## Tips

- Use `-t` for type filtering (f=file, d=directory, l=symlink, x=executable)
- `-e` for extension is simpler than `-g "*.ext"`
- `{}` in `-x` commands represents the found path
- `{.}` strips the extension
- `{/}` gets basename, `{//}` gets directory

## Documentation

GitHub: https://github.com/sharkdp/fd
Man page: `man fd`

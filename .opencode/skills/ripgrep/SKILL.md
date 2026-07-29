---
name: ripgrep
description: Blazingly fast text search tool - recursively searches directories for regex patterns with respect to gitignore rules.
homepage: https://github.com/BurntSushi/ripgrep
metadata: {"clawdbot":{"emoji":"🔎","requires":{"bins":["rg"]},"install":[{"id":"brew","kind":"brew","formula":"ripgrep","bins":["rg"],"label":"Install ripgrep (brew)"},{"id":"apt","kind":"apt","package":"ripgrep","bins":["rg"],"label":"Install ripgrep (apt)"}]}}
---

# ripgrep (rg)

Fast, smart recursive search. Respects `.gitignore` by default.

## Quick Start

### Basic search
```bash
# Search for "TODO" in current directory
rg "TODO"

# Case-insensitive search
rg -i "fixme"

# Search specific file types
rg "error" -t py       # Python files only
rg "function" -t js    # JavaScript files
```

### Common patterns
```bash
# Whole word match
rg -w "test"

# Show only filenames
rg -l "pattern"

# Show with context (3 lines before/after)
rg -C 3 "function"

# Count matches
rg -c "import"
```

## Advanced Usage

### File type filtering
```bash
# Multiple file types
rg "error" -t py -t js

# Exclude file types
rg "TODO" -T md -T txt

# List available types
rg --type-list
```

### Search modifiers
```bash
# Regex search
rg "user_\d+"

# Fixed string (no regex)
rg -F "function()"

# Multiline search
rg -U "start.*end"

# Only show matches, not lines
rg -o "https?://[^\s]+"
```

### Path filtering
```bash
# Search specific directory
rg "pattern" src/

# Glob patterns
rg "error" -g "*.log"
rg "test" -g "!*.min.js"

# Include hidden files
rg "secret" --hidden

# Search all files (ignore .gitignore)
rg "pattern" --no-ignore
```

## Replacement Operations

```bash
# Preview replacements
rg "old_name" --replace "new_name"

# Actually replace (requires extra tool like sd)
rg "old_name" -l | xargs sed -i 's/old_name/new_name/g'
```

## Performance Tips

```bash
# Parallel search (auto by default)
rg "pattern" -j 8

# Skip large files
rg "pattern" --max-filesize 10M

# Memory map files
rg "pattern" --mmap
```

## Common Use Cases

**Find TODOs in code:**
```bash
rg "TODO|FIXME|HACK" --type-add 'code:*.{rs,go,py,js,ts}' -t code
```

**Search in specific branches:**
```bash
git show branch:file | rg "pattern"
```

**Find files containing multiple patterns:**
```bash
rg "pattern1" | rg "pattern2"
```

**Search with context and color:**
```bash
rg -C 2 --color always "error" | less -R
```

## Comparison to grep

- **Faster:** Typically 5-10x faster than grep
- **Smarter:** Respects `.gitignore`, skips binary files
- **Better defaults:** Recursive, colored output, line numbers
- **Easier:** Simpler syntax for common tasks

## Tips

- `rg` is often faster than `grep -r`
- Use `-t` for file type filtering instead of `--include`
- Combine with other tools: `rg pattern -l | xargs tool`
- Add custom types in `~/.ripgreprc`
- Use `--stats` to see search performance

## Documentation

GitHub: https://github.com/BurntSushi/ripgrep
User Guide: https://github.com/BurntSushi/ripgrep/blob/master/GUIDE.md

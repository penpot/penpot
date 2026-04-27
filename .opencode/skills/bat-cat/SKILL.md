---
name: bat-cat
description: A cat clone with syntax highlighting, line numbers, and Git integration - a modern replacement for cat.
homepage: https://github.com/sharkdp/bat
metadata: {"clawdbot":{"emoji":"🦇","requires":{"bins":["bat"]},"install":[{"id":"brew","kind":"brew","formula":"bat","bins":["bat"],"label":"Install bat (brew)"},{"id":"apt","kind":"apt","package":"bat","bins":["bat"],"label":"Install bat (apt)"}]}}
---

# bat - Better cat

`cat` with syntax highlighting, line numbers, and Git integration.

## Quick Start

### Basic usage
```bash
# View file with syntax highlighting
bat README.md

# Multiple files
bat file1.js file2.py

# With line numbers (default)
bat script.sh

# Without line numbers
bat -p script.sh
```

### Viewing modes
```bash
# Plain mode (like cat)
bat -p file.txt

# Show non-printable characters
bat -A file.txt

# Squeeze blank lines
bat -s file.txt

# Paging (auto for large files)
bat --paging=always file.txt
bat --paging=never file.txt
```

## Syntax Highlighting

### Language detection
```bash
# Auto-detect from extension
bat script.py

# Force specific language
bat -l javascript config.txt

# Show all languages
bat --list-languages
```

### Themes
```bash
# List available themes
bat --list-themes

# Use specific theme
bat --theme="Monokai Extended" file.py

# Set default theme in config
# ~/.config/bat/config: --theme="Dracula"
```

## Line Ranges

```bash
# Show specific lines
bat -r 10:20 file.txt

# From line to end
bat -r 100: file.txt

# Start to specific line
bat -r :50 file.txt

# Multiple ranges
bat -r 1:10 -r 50:60 file.txt
```

## Git Integration

```bash
# Show Git modifications (added/removed/modified lines)
bat --diff file.txt

# Show decorations (Git + file header)
bat --decorations=always file.txt
```

## Output Control

```bash
# Output raw (no styling)
bat --style=plain file.txt

# Customize style
bat --style=numbers,changes file.txt

# Available styles: auto, full, plain, changes, header, grid, numbers, snip
bat --style=header,grid,numbers file.txt
```

## Common Use Cases

**Quick file preview:**
```bash
bat file.json
```

**View logs with syntax highlighting:**
```bash
bat error.log
```

**Compare files visually:**
```bash
bat --diff file1.txt
bat file2.txt
```

**Preview before editing:**
```bash
bat config.yaml && vim config.yaml
```

**Cat replacement in pipes:**
```bash
bat -p file.txt | grep "pattern"
```

**View specific function:**
```bash
bat -r 45:67 script.py  # If function is on lines 45-67
```

## Integration with other tools

**As pager for man pages:**
```bash
export MANPAGER="sh -c 'col -bx | bat -l man -p'"
man grep
```

**With ripgrep:**
```bash
rg "pattern" -l | xargs bat
```

**With fzf:**
```bash
fzf --preview 'bat --color=always --style=numbers {}'
```

**With diff:**
```bash
diff -u file1 file2 | bat -l diff
```

## Configuration

Create `~/.config/bat/config` for defaults:

```
# Set theme
--theme="Dracula"

# Show line numbers, Git modifications and file header, but no grid
--style="numbers,changes,header"

# Use italic text on terminal
--italic-text=always

# Add custom mapping
--map-syntax "*.conf:INI"
```

## Performance Tips

- Use `-p` for plain mode when piping
- Use `--paging=never` when output is used programmatically
- `bat` caches parsed files for faster subsequent access

## Tips

- **Alias:** `alias cat='bat -p'` for drop-in cat replacement
- **Pager:** Use as pager with `export PAGER="bat"`
- **On Debian/Ubuntu:** Command may be `batcat` instead of `bat`
- **Custom syntaxes:** Add to `~/.config/bat/syntaxes/`
- **Performance:** For huge files, use `bat --paging=never` or plain `cat`

## Common flags

- `-p` / `--plain`: Plain mode (no line numbers/decorations)
- `-n` / `--number`: Only show line numbers
- `-A` / `--show-all`: Show non-printable characters
- `-l` / `--language`: Set language for syntax highlighting
- `-r` / `--line-range`: Only show specific line range(s)

## Documentation

GitHub: https://github.com/sharkdp/bat
Man page: `man bat`
Customization: https://github.com/sharkdp/bat#customization

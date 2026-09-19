# Paren-Repair

`scripts/paren-repair` fixes mismatched parentheses, brackets, and braces in
Clojure/ClojureScript files, then reformats them with cljfmt.

## When to use

- After LLM edits introduce broken delimiters — proactively run it on files
  you just touched.
- When lint (clj-kondo), the Clojure compiler, or shadow-cljs report syntax
  errors mentioning mismatched/unclosed delimiters, reader errors, or
  unexpected EOF.
- Before running lint/format checks — delimiter errors make linter output
  misleading. Fix them first, then lint.

## How to use (CLI)

```bash
# File mode (in-place fix + format)
bb scripts/paren-repair path/to/file.clj

# Pipe mode (stdin → fixed code to stdout)
echo '(def x 1' | bb scripts/paren-repair

# Help
bb scripts/paren-repair --help
```
`bb` must be invoked from the repo root so the path `scripts/paren-repair` resolves.

## Native Tool Available (opencode)

A native opencode tool `paren-repair` is available (defined in
`.opencode/plugins/penpot.js`, which registers it for both opencode V1 via
`server()` and opencode V2 via `setup()`). The LLM can call it directly with:
- `files`: comma-separated file paths to fix (a string, not an array)
- `code`: Code string to fix via stdin

Example usage by the LLM:
```
paren-repair(files="src/foo.clj, src/bar.cljs")
paren-repair(code="(defn foo [x")
```



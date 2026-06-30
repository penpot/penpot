# Paren-Repair

`tools/paren-repair.bb` fixes mismatched parentheses, brackets, and braces in
Clojure/ClojureScript files, then reformats them with cljfmt.

## When to use

- After LLM edits introduce broken delimiters — proactively run it on files
  you just touched.
- When lint (clj-kondo), the Clojure compiler, or shadow-cljs report syntax
  errors mentioning mismatched/unclosed delimiters, reader errors, or
  unexpected EOF.
- Before running lint/format checks — delimiter errors make linter output
  misleading. Fix them first, then lint.

## How to use

```bash
# File mode (in-place fix + format)
bb tools/paren-repair.bb path/to/file.clj

# Pipe mode (stdin → fixed code to stdout)
echo '(def x 1' | bb tools/paren-repair.bb

# Help
bb tools/paren-repair.bb --help
```

`bb` must be invoked from the repo root so the path `tools/paren-repair.bb` resolves.

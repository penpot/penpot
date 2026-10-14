---
title: Resolver
desc: How Penpot's tokenscript resolver resolves token sets whose values reference each other, updates them incrementally and previews values.
---

# Resolver

The resolver (`app.common.tokenscript.processor`) resolves tokens whose
values reference each other, from one or several token sets. It never
throws for a bad token: errors are stored as the token's
[issues](/technical-guide/developer/subsystems/tokenscript/errors/#issues).

## Building

```clojure
(require '[app.common.tokenscript :as ts]
         '[app.common.tokenscript.processor :as processor])

(ts/build {"spacing.base"  {:value "8"}
           "spacing.large" {:value "{spacing.base} * 2"}
           "broken"        {:value "{missing}"}})
;; => {:tokens   {"spacing.base" #Number 8, "spacing.large" #Number 16, "broken" "{missing}"}
;;     :resolved {"spacing.base" #Number 8, "spacing.large" #Number 16, "broken" <error map>}
;;     :issues   {"broken" [<:processor/dependency-error>]}
;;     :graph    {"spacing.base" #{}, "spacing.large" #{"spacing.base"}, "broken" #{"missing"}}
;;     :state    ...}
```

`build` takes a map of token names to token data,
`{:value .. :type .. :metadata ..}`; plain values and `$value` / `$type`
keys work too. Its options are `:config` and `:object-parsers`.

### Sets

`build` also takes an ordered vector of sets, `[[set-key tokens] ..]`. A
token defined by several sets is taken from the last of them, and
references reach across sets:

```clojure
(def result
  (ts/build [[core-id  {"color.bg" {:value "#fff"} "space" {:value "8"}}]
             [theme-id {"color.bg" {:value "#000"}}]]))

(processor/source result "color.bg")    ;; => theme-id
(processor/metadata result "color.bg")  ;; => the :metadata of that token data
```

Set keys and `:metadata` are not interpreted. Penpot can pass the set
ids as keys and the token ids as metadata, and read them back for a
resolved name. A flat map is one set with the key nil.

| Key | |
|---|---|
| `:tokens` | Name to resolved value (a symbol), or the original value for a failed token. |
| `:resolved` | Name to resolved value or error map. |
| `:issues` | Name to a vector of issues; nil when there are none. |
| `:graph` | Name to the set of names it references. |
| `:state` | Input of the [incremental operations](#incremental-updates). |

`processor/tokens->strings` and `processor/tokens->data` convert
`:tokens` to strings or plain data.

## Resolution

A token is parsed in inline mode, and in script mode when it starts with
a statement keyword (see
[Language](/technical-guide/developer/subsystems/tokenscript/language/#inline-and-script-mode)).
Numbers, booleans, nil and the bare CSS keywords listed in
[Built-in functions](/technical-guide/developer/subsystems/tokenscript/functions/#other)
are taken as they are.

Resolution is a memoized depth first walk: resolving a token first
resolves everything it references, so the evaluation order comes out of
the recursion. A token that reaches itself gets
`:processor/circular-dependency`, as does every other token on the
cycle; a cycle can also pass through a group or a key of a dictionary
token. A token referencing a failed or missing token gets
`:processor/dependency-error`. Which tokens of a cycle get which of the
two depends on where resolution enters the cycle; Penpot shows both as a
circular reference.

A token name is a dot separated path whose segments contain no
whitespace, braces or brackets. Other names get
`:processor/invalid-token-name` and are still resolved.

### References

| Reference | Value |
|---|---|
| `{color.primary}` | The token `color.primary`. |
| `{color}` | A group: a dictionary of the tokens directly below `color`, keyed by the rest of their name. |
| `{typography.fontSize}` | A field of the structured token `typography`. |
| `{theme.dark}` | A key of the token `theme` whose value is a dictionary; one level deep. |

### Structured tokens

A token whose value is a map or a vector (typography, shadow) is a
structured token. Its string leaves are expressions, resolved like
tokens of their own under field paths such as `shadow[0].blur`. An
error in a field is reported on the token, with the field as `:path`
(`[0 "blur"]`).

`:object-parsers` turns maps into atomic values instead of fields: a
vector of `{:predicate :->symbol}` maps.
`token-data/number-with-unit-parser` reads `{:value 8 :unit "px"}` as
`8px`. There are none by default.

## Incremental updates

These functions take the `:state` of a result and return a new result.
The state they started from is not changed, so a form can preview an
edit on the shared state and drop the result.

| Function | |
|---|---|
| `(create-token state name data opts)` | Adds a token, to the last set by default. Raises `:processor/token-already-exists` when that set has it. |
| `(update-token state name data opts)` | Sets the data of a token, adding it when it does not exist. |
| `(rename-token state name new-name opts)` | Renames a token. References to it break unless `:update-references? true` rewrites them. |
| `(delete-token state name opts)` | Removes a token. Raises `:processor/token-not-found` when the set does not have it. |
| `(set-sets state sets)` | Replaces the sets, for a theme switch or a toggled set. |

`opts` is optional. Its `:set` names the set to change; by default it is
the set the token is taken from. An unknown set raises
`:processor/token-set-not-found`.

The token operations add `:dependents` to the result: the dependency
graph between the token and the tokens depending on it. For
`delete-token` it holds the tokens that referenced it. It is empty when
a later set overrides the changed definition, since nothing resolved
changes.

```clojure
(let [{:keys [state]} (ts/build {"a" {:value "8"} "b" {:value "{a} * 2"}})]
  (processor/update-token state "a" {:value "10"}))
;; => {:tokens {"a" #Number 10, "b" #Number 20} :dependents {"a" #{}, "b" #{"a"}} ...}
```

An operation re-parses only the tokens whose data changed and
re-resolves only what can observe them: the tokens referencing them,
directly or through a group (`{color}`) or a key of a dictionary token
(`{theme.dark}`), found through the reverse dependency graph the state
keeps. A missing token is in that graph too, so creating it fixes the
tokens waiting for it. Every other value is kept.

`affected-tokens`, `modified-dependents`, `broken-references`,
`tokens-with-issues`, `token-error`, `errors` and `issue-with-code?`
read a result.

## Previews

`(processor/resolve-value state value opts)` evaluates an expression
against the resolved tokens, for previews while the user types. It
does not change the state and returns `{:value symbol-or-nil :issues
[..]}`; errors are returned as issues, not raised. With `{:type
token-type}` the value is also validated.

The value is parsed in script mode, so unquoted dotted words such as
`http://foo.bar` fail here while the same token value resolves in a
build.

## Caches

Caches that live across builds, all keyed by inputs that fully
determine the result:

- Parsed token values, keyed by the value text (up to 50 000 entries).
  Every build parses all tokens, but on a set or theme switch most
  values were parsed before.
- URI resolution and conversion paths between schemas, in the `cache`
  field of each registry, replaced whenever a spec or conversion is
  added.
- Color conversions, for the last config used (up to 10 000 entries).
- Parsed schema URIs.
- The evaluated constants, for the last config used.

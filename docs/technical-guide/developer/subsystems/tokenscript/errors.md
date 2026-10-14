---
title: Errors and issues
desc: The error maps raised by Penpot's tokenscript interpreter, their codes, and the issues the resolver stores per token.
---

# Errors and issues

Every error of the lexer, parser, interpreter and resolver is an
`ex-info` whose data is an error map. The resolver does not throw: it
stores the same maps as the issues of the failed token, so errors are
plain data on both sides of the API.

## Error maps

```clojure
{:type    :tokenscript/error
 :kind    :interpreter              ; :lexer, :parser, :interpreter or :processor
 :code    :interpreter/unknown-reference
 :hint    "Unknown reference: x"    ; the message without position
 :message "Line 1: Unknown reference: x\nNear token: x"
 :data    {:reference "x"}          ; depends on the code
 :line    1
 :token   {:type :reference :value "x" :line 1 :pos 1 :end-pos 2}}
```

The namespace of the code is the area that raised it: `lexer`,
`parser`, `interpreter`, `operations`, `symbols`, `functions`,
`config`, `processor`, `color` or `unit`. The codes and their messages
are listed in `app.common.tokenscript.errors/messages`.
`errors/code->compliance-code` gives the identifier the other
tokenscript implementations and the compliance suite use:
`:interpreter/unknown-reference` is `INT_UNKNOWN_REFERENCE`.

| Function | |
|---|---|
| `errors/raise` | Throws the error for a code: `(raise :processor/token-not-found {:data {:token-name "a"}})`. |
| `errors/error-map` | The error map, without throwing. |
| `errors/error-map?` | Whether a value is an error map. |
| `errors/->error-map` | The error map of an exception or an error map. |
| `errors/describe` | A message for any exception, to wrap foreign errors. |

## Issues

`:issues` of a build result maps a token name to a vector of issues of
two kinds:

| Issue | | The token |
|---|---|---|
| Error map (`:type :tokenscript/error`) | The value could not be resolved. | Keeps its original value in `:tokens`. |
| Validation issue (`:type :tokenscript/issue`) | The value failed its [token type](/technical-guide/developer/subsystems/tokenscript/validation/). | Keeps its resolved value. |

A validation issue:

```clojure
{:type       :tokenscript/issue
 :code       "INVALID_OPACITY_RANGE"      ; returned by the validation script
 :severity   :warning
 :message    "Token validation failed: INVALID_OPACITY_RANGE"
 :token-name "faded"
 :path       nil                          ; [0 "blur"] for a nested field
 :data       {:token-type "opacity"}}
```

### Resolver errors

`:processor/dependency-error` is the error of a token that references a
failed or missing token. `:chain` in its data lists the token names
down to the failing one and `:cause` holds the error map of the root
cause, so callers can match the root cause's code:

```clojure
{:code :processor/dependency-error
 :data {:token-name "j"
        :chain      ["j" "i" "missing"]
        :cause      {:code :processor/token-not-found :data {:token-name "missing"} ...}}
 ...}
```

`:processor/circular-dependency` names the token in `:tokens`. An
error in a field of a structured token is stored on the token with the
field as `:path`, and its message starts with `Error at <path>:`.

`app.main.data.tokenscript` maps these issues to the error codes of the
token UI (`:error.style-dictionary/missing-reference`,
`:error.token/circular-reference`, ...).

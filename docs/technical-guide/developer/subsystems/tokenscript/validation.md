---
title: Token validation
desc: How token type schemas validate resolved token values in Penpot's tokenscript resolver, including nested values and the validation issues they produce.
---

# Token validation

A token type schema checks the resolved value of every token of its
type. A failed check adds a warning to the token's issues; the token
keeps its value.

The bundled schemas define no token types, so `default-config` validates
nothing. The workspace checks token values itself in
`app.main.data.tokenscript` (opacity range, font weight, ...).

## Token type schemas

```clojure
(require '[app.common.tokenscript :as ts]
         '[app.common.tokenscript.config :as config]
         '[app.common.tokenscript.processor :as processor])

(def cfg
  (config/register-schemas
   (ts/default-config)
   [{:uri "/api/v1/core/opacity/0/"
     :schema {:name "opacity"
              :type "token"
              :validation {:script "if ({input} < 0 || {input} > 1) [ return \"INVALID_OPACITY_RANGE\"; ]; return true;"}}}]))

(:issues (processor/build {"faded" {:value "1.5" :type "opacity"}} {:config cfg}))
;; => {"faded" [{:type :tokenscript/issue :code "INVALID_OPACITY_RANGE" :severity :warning ...}]}
```

| Key | |
|---|---|
| `name` | The token type, matched case insensitively against the `:type` of the token data. |
| `validation.script` | The validation script. |
| `schema` | The shape of structured values, for [nested validation](#nested-values). |

## Validation scripts

The script gets the resolved value as `{input}` and returns `true` when
it is valid, or an error code string. Codes follow
`INVALID_<TYPE>_<REASON>`.

Other results become codes too:

| Result | Code |
|---|---|
| anything but `true` or a string | `VALIDATION_INVALID_RESULT` |
| the script raises | `VALIDATION_ERROR: <message>` |
| the script does not parse | `VALIDATION_ERROR: invalid validation script: <script>` |

`token-types/validation-error-types` lists the codes the scripts of a
registry can return, read from their `return "..."` statements.

## Nested values

When the value is valid, its fields are validated by their own types.
`schema` names them with `{"type": "token", "url": <token type URI>}`,
in `properties` for an object or in `items` for a list:

```json
{
  "name": "shadow",
  "type": "token",
  "schema": {
    "type": "list",
    "items": {
      "type": "object",
      "properties": {
        "color": { "type": "token", "url": "/api/v1/core/color/0/" },
        "blur": { "type": "token", "url": "/api/v1/core/dimension/0/" }
      }
    }
  }
}
```

A failure in a field carries its `:path` (`[0 "blur"]`). A type that
reaches itself again fails with `CIRCULAR_REFERENCE`, and validation
stops at a depth of 10 with `MAX_VALIDATION_DEPTH_EXCEEDED`.

## API

| Function | |
|---|---|
| `validation/validate` | Validates a symbol as a token type. Returns `{:valid? :error :token-type :path}` results. |
| `validation/type-issues` | The failures as issues, as the resolver stores them; see [Errors and issues](/technical-guide/developer/subsystems/tokenscript/errors/#issues). |
| `token-types/register-validation` | Sets the validation script of a token type in a registry. |

`processor/resolve-value` validates its value when given `:type`.

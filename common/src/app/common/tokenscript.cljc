;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript
  "Tokenscript: the design token expression language used by Penpot to
  resolve token values.

  This namespace is the entry point; the implementation lives in the
  `app.common.tokenscript.*` namespaces:

    lexer, parser, ast       source text to AST
    interpreter, operations  evaluation
    symbols                  runtime values (numbers, colors, lists, ...)
    config + registries      schemas: color spaces, units, functions,
                             token types (`colors`, `units`, `functions`,
                             `token-types`)
    processor                dependency resolution of token sets
    errors                   structured error maps

  Typical use:

    (def cfg (default-config))

    (evaluate \"lighten(#ff0000, 0.2)\" {:config cfg})
    ;; => color symbol

    (process-tokens {\"base\" {:value \"8\"}
                     \"double\" {:value \"{base} * 2px\"}}
                    {:config cfg})
    ;; => {:tokens {...} :issues nil :graph {...} :state ...}

  `default-config` includes the schemas shipped with Penpot (see
  `app.common.tokenscript.bundle`)."
  (:require
   [app.common.tokenscript.bundle :as bundle]
   [app.common.tokenscript.config :as config]
   [app.common.tokenscript.interpreter :as interp]
   [app.common.tokenscript.parser :as parser]
   [app.common.tokenscript.processor :as processor]
   [app.common.tokenscript.symbols :as sym]))

;; Config ---------------------------------------------------------------------

(def ^:private default-config*
  (delay (config/register-schemas (config/make-config) bundle/schemas)))

(defn default-config
  "Config with the bundled Penpot schemas (color spaces, color functions
  and CSS named colors). Built once."
  []
  @default-config*)

(defn make-config
  "Config with only the core types (hex colors, default units). Pass
  `:schemas` to register extra schemas."
  ([] (config/make-config))
  ([{:keys [schemas] :as opts}]
   (cond-> (config/make-config opts)
     (seq schemas) (config/register-schemas schemas))))

(def register-schemas config/register-schemas)
(def register-schema config/register-schema)

(def bundled-schemas bundle/schemas)

;; Expressions ----------------------------------------------------------------

(defn parse
  "Parses an expression. See `app.common.tokenscript.parser/parse`."
  ([text] (parser/parse text))
  ([text opts] (parser/parse text opts)))

(defn references
  "Reference paths used by an expression."
  [text]
  (parser/references text {:mode :inline}))

(defn evaluate
  "Parses and evaluates `text`. Options:

    :references map of reference path to value
    :config     defaults to `default-config`
    :mode       `:script` (default) or `:inline`

  Returns a symbol (see `app.common.tokenscript.symbols`) or nil."
  ([text] (evaluate text nil))
  ([text opts]
   (interp/evaluate text (merge {:config (default-config)} opts))))

(defn evaluate->string
  "`evaluate` rendered as a string."
  ([text] (evaluate->string text nil))
  ([text opts]
   (let [cfg (or (:config opts) (default-config))]
     (sym/->string-result (evaluate text (assoc opts :config cfg)) cfg))))

(defn interpret
  "Evaluates a parsed AST. See `app.common.tokenscript.interpreter/interpret`."
  ([ast] (interp/interpret ast {:config (default-config)}))
  ([ast opts] (interp/interpret ast (merge {:config (default-config)} opts))))

;; Tokens ---------------------------------------------------------------------

(defn process-tokens
  "Resolves a token map. See `app.common.tokenscript.processor/process-tokens`."
  ([tokens] (process-tokens tokens nil))
  ([tokens opts]
   (processor/process-tokens tokens (merge {:config (default-config)} opts))))

(defn build
  "Resolves normalized token data. See `app.common.tokenscript.processor/build`."
  ([tokens] (build tokens nil))
  ([tokens opts]
   (processor/build tokens (merge {:config (default-config)} opts))))

(def update-token processor/update-token)
(def create-token processor/create-token)
(def delete-token processor/delete-token)
(def resolve-value processor/resolve-value)
(def dependents processor/dependents)
(def affected-tokens processor/affected-tokens)
(def broken-references processor/broken-references)
(def modified-dependants processor/modified-dependants)
(def token-error processor/token-error)
(def token-errors processor/errors)
(def tokens->data processor/tokens->data)
(def tokens->strings processor/tokens->strings)

;; Values ---------------------------------------------------------------------

(def value? sym/symbol?)
(def ->data sym/->data)
(def ->string sym/->string-result)

(defn color->hex
  "Converts any color symbol to its hex representation, keeping alpha."
  ([color] (color->hex (default-config) color))
  ([config color]
   (interp/color->type config color "hex")))

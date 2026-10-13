;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript
  "Tokenscript: the design token expression language used by Penpot to
  resolve token values.

  This namespace resolves token sets with the schemas shipped with
  Penpot; the implementation lives in the `app.common.tokenscript.*`
  namespaces, which can be used directly for anything else:

    lexer, parser, ast       source text to AST
    interpreter, operations  evaluation
    symbols                  runtime values (numbers, colors, lists, ...)
    config + registries      schemas: color spaces, units, functions,
                             token types (`colors`, `units`, `functions`,
                             `token-types`)
    processor                dependency resolution of token sets and
                             incremental updates
    errors                   structured error maps

  Typical use:

    (build {\"base\" {:value \"8\"}
            \"double\" {:value \"{base} * 2px\"}})
    ;; => {:tokens {...} :issues nil :graph {...} :state ...}"
  (:require
   [app.common.tokenscript.config :as config]
   [app.common.tokenscript.interpreter :as interp]
   [app.common.tokenscript.processor :as processor]
   [app.common.tokenscript.schemas :as schemas]))

(def ^:private default-config*
  (delay (config/register-schemas (config/make-config) (schemas/schemas))))

(defn default-config
  "Config with the bundled Penpot schemas (color spaces, color functions
  and CSS named colors). Built once."
  []
  @default-config*)

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

(defn color->hex
  "Converts any color symbol to its hex representation, keeping alpha."
  ([color] (color->hex (default-config) color))
  ([config color]
   (interp/color->type config color "hex")))

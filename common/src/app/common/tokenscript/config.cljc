;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript.config
  "Interpreter configuration: an immutable `Config` record holding the
  schema registries and language options.

    language-options  {:max-iterations 1000}
    colors            color registry
    units             unit registry
    functions         function registry
    tokens            token type registry
    inline-constants  {name value}

  Build one with `make-config` and extend it with `register-schemas`."
  (:require
   [app.common.tokenscript.colors :as colors]
   [app.common.tokenscript.functions :as functions]
   [app.common.tokenscript.registry :as reg]
   [app.common.tokenscript.symbols :as sym]
   [app.common.tokenscript.token-types :as token-types]
   [app.common.tokenscript.units :as units]
   [clojure.string :as str]
   [clojure.walk :as walk]))

(def default-language-options
  {:max-iterations 1000})

(defrecord Config [language-options colors units functions tokens inline-constants])

(defn make-config
  "Creates a config with the default color (hex) and unit registries."
  []
  (->Config default-language-options
            (colors/default-registry)
            (units/default-registry)
            (functions/empty-registry)
            (token-types/empty-registry)
            {}))

(defn register-schema
  "Registers one schema (`{:uri .. :schema ..}`) by its `:type`:
  `color`, `function`, `token` or `constants`. Unknown types are ignored."
  [config {:keys [uri schema]}]
  (let [schema (walk/keywordize-keys schema)]
    (case (:type schema)
      "color" (update config :colors reg/register uri schema)
      "function" (update config :functions reg/register uri schema)
      "token" (update config :tokens reg/register uri schema)
      "unit" (update config :units reg/register uri schema)
      "constants" (if (:inline schema)
                    (update config :inline-constants
                            into
                            (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) v]))
                            (:values schema))
                    config)
      config)))

(defn register-schemas
  [config schemas]
  (reduce register-schema config schemas))

(def valid-type-names
  "Type names accepted in variable declarations, in the order used by
  error messages."
  ["number" "string" "boolean" "numberwithunit" "color" "null" "list" "dictionary" "token"])

(defn empty-of-type
  "Empty (null) value of the declared type `base-type.sub-type`, e.g.
  `Color.RGB` -> a null RGB color. Nil when the type is not defined."
  [config base-type sub-type]
  (let [base (str/lower-case base-type)]
    (cond
      (= base "color") (cond
                         (nil? sub-type) (sym/color nil)
                         (colors/spec-by-type (:colors config) sub-type) (sym/color nil sub-type))
      (some? sub-type) nil
      :else (sym/empty-of-type base))))

(defn max-iterations
  [config]
  (get-in config [:language-options :max-iterations] (:max-iterations default-language-options)))

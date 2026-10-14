;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript.token-types
  "Token type registry.

  Token type schemas (`:type \"token\"`) describe structured token values
  (typography, shadow, ...) and may carry a tokenscript validation script
  that receives `{input}` and returns `true` or an error code string.
  Validation execution lives in `app.common.tokenscript.validation`; see
  docs/technical-guide/developer/subsystems/tokenscript/validation.md.

  `TokenTypeRegistry` has the common registry fields (see
  `app.common.tokenscript.registry`) plus `validation`
  (`{name-key {:script .. :ast ..}}`)."
  (:require
   [app.common.tokenscript.ast :as ast]
   [app.common.tokenscript.errors :as err]
   [app.common.tokenscript.parser :as parser]
   [app.common.tokenscript.registry :as reg]
   [app.common.tokenscript.schema-uri :as suri]
   [app.common.tokenscript.util :as u]
   [clojure.walk :as walk]))

(def valid-schema-types ["number" "string" "token" "object" "list"])

(defn- normalize-spec
  [uri spec]
  (let [spec (walk/keywordize-keys spec)]
    (when-not (and (map? spec) (string? (:name spec)) (= "token" (:type spec)))
      (err/raise :config/invalid-specification
                 {:data {:kind "token" :uri uri :error "expected a map with :name and :type \"token\""}}))
    spec))

(defn- parse-validation
  [script]
  (try
    {:script script :ast (:ast (parser/parse script))}
    (catch #?(:clj Throwable :cljs :default) _
      {:script script :ast nil})))

(defn register-validation
  "Registers a validation script for `token-type`. `validation` is the
  script string or a map with a `:script` key."
  [registry token-type validation]
  (let [script (if (string? validation) validation (:script validation))]
    (assoc-in registry [:validation (u/name-key token-type)] (parse-validation script))))

(defn- register-spec
  [registry uri spec]
  (let [uri (suri/normalize uri)
        spec (normalize-spec uri spec)
        registry (reg/add-spec registry uri spec)]
    (if-let [validation (:validation spec)]
      (register-validation registry (:name spec) validation)
      registry)))

(defrecord TokenTypeRegistry [specs spec-types validation cache]
  reg/ISchemaRegistry
  (register [this uri spec]
    (register-spec this uri spec)))

(defn empty-registry
  []
  (->TokenTypeRegistry {} {} {} (reg/new-cache)))

(defn validation
  "Validation entry (`{:script :ast}`) for a token type."
  [registry token-type]
  (u/get-name (:validation registry) token-type))

(defn- error-types-of
  [registry token-type]
  (when-let [tree (:ast (validation registry token-type))]
    (->> (ast/filter-nodes tree #(= :return (:node %)))
         (keep (fn [{:keys [expr]}] (when (= :string (:node expr)) (:value expr))))
         (vec))))

(defn validation-error-types
  "Error code strings returned by the validation scripts, for one token
  type or for all registered types."
  ([registry]
   (vec (distinct (mapcat #(error-types-of registry %) (keys (:validation registry))))))
  ([registry token-type]
   (or (error-types-of registry token-type) [])))

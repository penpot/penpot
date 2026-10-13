;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript.validation
  "Token value validation against registered token type schemas.

  A validation result is `{:valid? bool :error code :token-type type
  :path [..]}`; `:path` points at the nested field for failures inside
  structured values."
  (:require
   [app.common.tokenscript.errors :as err]
   [app.common.tokenscript.interpreter :as interp]
   [app.common.tokenscript.registry :as reg]
   [app.common.tokenscript.symbols :as sym]
   [app.common.tokenscript.token-types :as token-types]
   [clojure.string :as str]))

(def max-depth 10)

(defn- failure
  [error token-type path]
  (cond-> {:valid? false :error error :token-type token-type}
    (seq path) (assoc :path path)))

(defn- run-script
  [config token-type value path]
  (let [{:keys [ast script] :as validation} (token-types/validation (:tokens config) token-type)]
    (cond
      (nil? validation)
      {:valid? true :token-type token-type}

      (nil? ast)
      (failure (str "VALIDATION_ERROR: invalid validation script: " script) token-type path)

      :else
      (try
        (let [result (interp/interpret ast {:references {"input" value} :config config})]
          (cond
            (and (sym/boolean? result) (true? (:value result))) {:valid? true :token-type token-type}
            (sym/string? result) (failure (:value result) token-type path)
            :else (failure "VALIDATION_INVALID_RESULT" token-type path)))
        (catch #?(:clj Throwable :cljs :default) e
          (failure (str "VALIDATION_ERROR: " (err/describe e)) token-type path))))))

(defn- value-map
  [value]
  (cond
    (sym/dictionary? value) (sym/value value)
    (and (sym/token? value) (map? (sym/value value))) (sym/value value)
    :else nil))

(declare validate*)

(defn- validate-properties
  [config m properties visited path depth]
  (reduce-kv (fn [results prop-name prop-spec]
               (let [prop-name (name prop-name)]
                 (if (not (and (= "token" (:type prop-spec)) (:url prop-spec)))
                   results
                   (cond
                     (contains? visited (:url prop-spec))
                     (conj results (failure "CIRCULAR_REFERENCE" (:url prop-spec) (conj path prop-name)))

                     :else
                     (let [prop-value (get m prop-name)
                           referenced (reg/spec (:tokens config) (:url prop-spec))]
                       (if (and prop-value referenced)
                         (into results
                               (remove :valid?)
                               (validate* config (:name referenced) prop-value
                                          (conj visited (:url prop-spec)) (conj path prop-name) (inc depth)))
                         results))))))
             []
             properties))

(defn- validate-items
  [config value items-spec visited path depth]
  (if-not (sym/list? value)
    []
    (reduce (fn [results [i item]]
              (let [item-path (conj path i)]
                (cond-> results
                  (and (= "object" (:type items-spec)) (:properties items-spec))
                  (into (if-let [m (value-map item)]
                          (validate-properties config m (:properties items-spec) visited item-path (inc depth))
                          []))

                  (and (= "token" (:type items-spec)) (:url items-spec))
                  (into (if-let [referenced (reg/spec (:tokens config) (:url items-spec))]
                          (remove :valid?
                                  (validate* config (:name referenced) item
                                             (conj visited (:url items-spec)) item-path (inc depth)))
                          [])))))
            []
            (map-indexed vector (sym/value value)))))

(defn- validate*
  [config token-type value visited path depth]
  (if (> depth max-depth)
    [(failure "MAX_VALIDATION_DEPTH_EXCEEDED" token-type path)]
    (let [top (run-script config token-type value path)]
      (if-not (:valid? top)
        [top]
        (let [spec (token-types/spec-by-type (:tokens config) token-type)
              schema (:schema spec)
              results (cond
                        (nil? schema) []
                        (and (= "list" (:type schema)) (:items schema))
                        (validate-items config value (:items schema) visited path depth)
                        (nil? (:properties schema)) []
                        :else (if-let [m (value-map value)]
                                (validate-properties config m (:properties schema) visited path depth)
                                []))]
          (if (seq results)
            results
            [{:valid? true :token-type token-type}]))))))

(defn validate
  "Validates `value` (a symbol) as a `token-type`. Returns a vector of
  results; a single valid result when everything passes."
  [config token-type value]
  (validate* config token-type value #{} [] 0))

(defn type-issues
  "Validation issues for a resolved token value. Returns `[]` when the
  type has no registered spec or the value is not a symbol."
  [config token-name token-type value]
  (if-not (and config (token-types/spec-by-type (:tokens config) token-type) (sym/symbol? value))
    []
    (let [to-validate (if (sym/token? value)
                        (let [inner (sym/value value)]
                          (if (map? inner)
                            (sym/dictionary inner)
                            (sym/list inner false)))
                        value)]
      (into []
            (keep (fn [{:keys [valid? error path token-type*] :as result}]
                    (when (and (not valid?) error)
                      (let [nested? (seq path)]
                        {:type :tokenscript/issue
                         :code error
                         :severity :warning
                         :message (if nested?
                                    (str "Validation failed at " (str/join "." (map str path)) ": " error)
                                    (str "Token validation failed: " error))
                         :token-name token-name
                         :path path
                         :data (if nested?
                                 {:token-type (or token-type* (:token-type result)) :parent-token-type token-type}
                                 {:token-type token-type})}))))
            (validate config token-type to-validate)))))

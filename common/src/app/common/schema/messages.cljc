;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.schema.messages
  (:require
   [app.common.data :as d]
   [app.common.i18n :as i18n :refer [tr]]
   [app.common.schema :as sm]
   [cuerdas.core :as str]
   [malli.core :as m]))

;; --- Handlers Helpers

(defn- translate-code
  [code]
  (if (vector? code)
    (tr (nth code 0) (i18n/c (nth code 1)))
    (tr code)))

(defn- handle-error-fn
  [props problem]
  (let [v-fn   (:error/fn props)
        result (v-fn problem)]
    (if (string? result)
      {:message result}
      {:message (or (some-> (get result :code)
                            (translate-code))
                    (get result :message)
                    (tr "errors.invalid-data"))})))

(defn- handle-error-message
  [props]
  {:message (get props :error/message)})

(defn- handle-error-code
  [props]
  (let [code (get props :error/code)]
    {:message (translate-code code)}))

(defn- interpret-schema-problem
  [acc {:keys [schema in value type] :as problem}]
  (let [props  (m/properties schema)
        tprops (m/type-properties schema)
        field  (or (:error/field props)
                   in)
        field  (if (vector? field)
                 field
                 [field])]

    (if (and (= 1 (count field))
             (contains? acc (first field)))
      acc
      (cond
        (or (nil? field)
            (empty? field))
        acc

        (or (= type :malli.core/missing-key)
            (nil? value))
        (assoc-in acc field {:message (tr "errors.field-missing")})

        ;; --- CHECK on schema props
        (contains? props :error/fn)
        (assoc-in acc field (handle-error-fn props problem))

        (contains? props :error/message)
        (assoc-in acc field (handle-error-message props))

        (contains? props :error/code)
        (assoc-in acc field (handle-error-code props))

        ;; --- CHECK on type props
        (contains? tprops :error/fn)
        (assoc-in acc field (handle-error-fn tprops problem))

        (contains? tprops :error/message)
        (assoc-in acc field (handle-error-message tprops))

        (contains? tprops :error/code)
        (assoc-in acc field (handle-error-code tprops))

        :else
        (assoc-in acc field {:message (tr "errors.invalid-data")})))))

(defn error-messages
  [explain]
  (->> (:errors explain)
       (reduce interpret-schema-problem {})
       (vals)
       (mapcat seq)
       (map (fn [[field {:keys [message]}]]
              (tr "plugins.validation.message" (name field) message)))
       (str/join ". ")))

(defn- apply-validators
  [validators state errors]
  (reduce (fn [errors validator-fn]
            (merge errors (validator-fn errors (:data state))))
          errors
          validators))

(defn collect-schema-errors
  [schema validators state]
  (let [explain (sm/explain schema (:data state))
        errors  (->> (reduce interpret-schema-problem {} (:errors explain))
                     (apply-validators validators state))]

    (-> (:errors state)
        (merge errors)
        (d/without-nils)
        (not-empty))))

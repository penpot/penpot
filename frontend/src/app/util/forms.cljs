;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.forms
  (:refer-clojure :exclude [uuid])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [malli.core :as m]
   [rumext.v2 :as mf]))

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
        field  (or (first in)
                   (:error/field props))]

    (if (contains? acc field)
      acc
      (cond
        (nil? field)
        acc

        (or (= type :malli.core/missing-key)
            (nil? value))
        (assoc acc field {:message (tr "errors.field-missing")})

        ;; --- CHECK on schema props
        (contains? props :error/fn)
        (assoc acc field (handle-error-fn props problem))

        (contains? props :error/message)
        (assoc acc field (handle-error-message props))

        (contains? props :error/code)
        (assoc acc field (handle-error-code props))

        ;; --- CHECK on type props
        (contains? tprops :error/fn)
        (assoc acc field (handle-error-fn tprops problem))

        (contains? tprops :error/message)
        (assoc acc field (handle-error-message tprops))

        (contains? tprops :error/code)
        (assoc acc field (handle-error-code tprops))

        :else
        (assoc acc field {:message (tr "errors.invalid-data")})))))

(defn- use-rerender-fn
  []
  (let [state     (mf/useState 0)
        render-fn (aget state 1)]
    (mf/use-fn
     (mf/deps render-fn)
     (fn []
       (render-fn inc)))))

(defn- apply-validators
  [validators state errors]
  (reduce (fn [errors validator-fn]
            (merge errors (validator-fn errors (:data state))))
          errors
          validators))

(defn- collect-schema-errors
  [schema validators state]
  (let [explain (sm/explain schema (:data state))
        errors  (->> (reduce interpret-schema-problem {} (:errors explain))
                     (apply-validators validators state))]

    (-> (:errors state)
        (merge errors)
        (d/without-nils)
        (not-empty))))

(defn- wrap-update-schema-fn
  [f {:keys [schema validators]}]
  (fn [& args]
    (let [state   (apply f args)
          cleaned (sm/decode schema (:data state) sm/string-transformer)
          valid?  (sm/validate schema cleaned)
          errors  (when-not valid?
                    (collect-schema-errors schema validators state))]

      (assoc state
             :errors errors
             :clean-data (when valid? cleaned)
             :valid (and (not errors) valid?)))))

(defn- create-form-mutator
  [internal-state rerender-fn wrap-update-fn initial opts]
  (mf/set-ref-val! internal-state initial)

  (reify
    IDeref
    (-deref [_]
      (mf/ref-val internal-state))

    IReset
    (-reset! [_ new-value]
      (if (nil? new-value)
        (mf/set-ref-val! internal-state (if (fn? initial) (initial) initial))
        (mf/set-ref-val! internal-state new-value))
      (rerender-fn))

    ISwap
    (-swap! [_ f]
      (let [f (wrap-update-fn f opts)]
        (mf/set-ref-val! internal-state (f (mf/ref-val internal-state)))
        (rerender-fn)))

    (-swap! [_ f x]
      (let [f (wrap-update-fn f opts)]
        (mf/set-ref-val! internal-state (f (mf/ref-val internal-state) x))
        (rerender-fn)))

    (-swap! [_ f x y]
      (let [f (wrap-update-fn f opts)]
        (mf/set-ref-val! internal-state (f (mf/ref-val internal-state) x y))
        (rerender-fn)))

    (-swap! [_ f x y more]
      (let [f (wrap-update-fn f opts)]
        (mf/set-ref-val! internal-state (apply f (mf/ref-val internal-state) x y more))
        (rerender-fn)))))

(defn use-form
  [& {:keys [initial] :as opts}]
  (let [rerender-fn (use-rerender-fn)

        initial
        (mf/with-memo [initial]
          {:data (if (fn? initial) (initial) initial)
           :errors {}
           :touched {}})

        internal-state
        (mf/use-ref nil)

        form-mutator
        (mf/with-memo [initial]
          (create-form-mutator internal-state rerender-fn wrap-update-schema-fn initial opts))]

    ;; Initialize internal state once
    (mf/with-layout-effect []
      (mf/set-ref-val! internal-state initial))

    (mf/with-effect [initial]
      (swap! form-mutator d/deep-merge initial))

    form-mutator))

(defn on-input-change
  ([form field value]
   (on-input-change form field value false))
  ([form field value trim?]
   (swap! form (fn [state]
                 (-> state
                     (assoc-in [:data field] (if trim? (str/trim value) value))
                     (update :errors dissoc field))))))

(defn update-input-value!
  [form field value]
  (swap! form (fn [state]
                (-> state
                    (assoc-in [:data field] value)
                    (update :errors dissoc field)))))

(defn on-input-blur
  [form field]
  (fn [_]
    (let [touched (get @form :touched)]
      (when-not (get touched field)
        (swap! form assoc-in [:touched field] true)))))

;; --- Helper Components

(defn error-class
  [form field]
  (when (and (dm/get-in form [:errors field])
             (dm/get-in form [:touched field]))
    "invalid"))

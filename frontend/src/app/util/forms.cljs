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
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [malli.core :as m]
   [rumext.v2 :as mf]))

;; --- Handlers Helpers

(defn- interpret-schema-problem
  [acc {:keys [schema in value] :as problem}]
  (let [props (merge (m/type-properties schema)
                     (m/properties schema))
        field (or (first in) (:error/field props))]

    (if (contains? acc field)
      acc
      (cond
        (nil? value)
        (assoc acc field {:code "errors.field-missing"})

        (contains? props :error/code)
        (assoc acc field {:code (:error/code props)})

        (contains? props :error/message)
        (assoc acc field {:code (:error/message props)})

        (contains? props :error/fn)
        (let [v-fn (:error/fn props)
              code (v-fn problem)]
          (assoc acc field {:code code}))

        (contains? props :error/validators)
        (let [validators (:error/validators props)
              props      (reduce #(%2 %1 value) props validators)]
          (assoc acc field {:code (d/nilv (:error/code props) "errors.invalid-data")}))

        :else
        (assoc acc field {:code "errors.invalid-data"})))))

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

        internal-state
        (mf/use-ref nil)

        form-mutator
        (mf/with-memo [initial]
          (create-form-mutator internal-state rerender-fn wrap-update-schema-fn initial opts))]

    ;; Initialize internal state once
    (mf/with-effect []
      (mf/set-ref-val! internal-state
                       {:data {}
                        :errors {}
                        :touched {}}))

    (mf/with-effect [initial]
      (if (fn? initial)
        (swap! form-mutator update :data merge (initial))
        (swap! form-mutator update :data merge initial)))

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

(mf/defc field-error
  [{:keys [form field type]
    :as props}]
  (let [{:keys [message] :as error} (dm/get-in form [:errors field])
        touched? (dm/get-in form [:touched field])
        show? (and touched? error message
                   (cond
                     (nil? type) true
                     (keyword? type) (= (:type error) type)
                     (ifn? type) (type (:type error))
                     :else false))]
    (when show?
      [:ul
       [:li {:key (:code error)} (tr (:message error))]])))

(defn error-class
  [form field]
  (when (and (dm/get-in form [:errors field])
             (dm/get-in form [:touched field]))
    "invalid"))

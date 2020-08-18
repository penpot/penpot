;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.util.forms
  (:refer-clojure :exclude [uuid])
  (:require
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [rumext.alpha :as mf]
   [app.common.spec :as us]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]))

;; --- Handlers Helpers

(defn- impl-mutator
  [v update-fn]
  (specify v
    IReset
    (-reset! [_ new-value]
      (update-fn new-value))

    ISwap
    (-swap!
      ([self f] (update-fn f))
      ([self f x] (update-fn #(f % x)))
      ([self f x y] (update-fn #(f % x y)))
      ([self f x y more] (update-fn #(apply f % x y more))))))

(defn- interpret-problem
  [acc {:keys [path pred val via in] :as problem}]
  ;; (prn "interpret-problem" problem)
  (cond
    (and (empty? path)
         (list? pred)
         (= (first (last pred)) 'cljs.core/contains?))
    (let [path (conj path (last (last pred)))]
      (assoc-in acc path {:code ::missing :type :builtin}))

    (and (not (empty? path))
         (not (empty? via)))
    (assoc-in acc path {:code (last via) :type :builtin})

    :else acc))

(defn use-form
  [& {:keys [spec validators initial]}]
  (let [[state update-state] (mf/useState {:data (if (fn? initial) (initial) initial)
                                           :errors {}
                                           :touched {}})

        cleaned  (s/conform spec (:data state))
        problems (when (= ::s/invalid cleaned)
                   (::s/problems (s/explain-data spec (:data state))))

        errors   (merge (reduce interpret-problem {} problems)
                        (reduce (fn [errors vf]
                                  (merge errors (vf (:data state))))
                                {} validators)
                        (:errors state))]
    (-> (assoc state
               :errors errors
               :clean-data (when (not= cleaned ::s/invalid) cleaned)
               :valid (and (empty? errors)
                           (not= cleaned ::s/invalid)))
        (impl-mutator update-state))))

(defn on-input-change
  ([{:keys [data] :as form} field]
   (on-input-change form field false))

  ([{:keys [data] :as form} field trim?]
  (fn [event]
    (let [target (dom/get-target event)
          value (dom/get-value target)]
      (swap! form (fn [state]
                    (-> state
                        (assoc-in [:data field] (if trim? (str/trim value) value))
                        (update :errors dissoc field))))))))

(defn on-input-blur
  [{:keys [touched] :as form} field]
  (fn [event]
    (let [target (dom/get-target event)]
      (when-not (get touched field)
        (swap! form assoc-in [:touched field] true)))))

;; --- Helper Components

(mf/defc field-error
  [{:keys [form field type]
    :or {only (constantly true)}
    :as props}]
  (let [{:keys [code message] :as error} (get-in form [:errors field])
        touched? (get-in form [:touched field])
        show? (and touched? error message
                   (cond
                     (nil? type) true
                     (keyword? type) (= (:type error) type)
                     (ifn? type) (type (:type error))
                     :else false))]
    (when show?
      [:ul.form-errors
       [:li {:key (:code error)} (tr (:message error))]])))

(defn error-class
  [form field]
  (when (and (get-in form [:errors field])
             (get-in form [:touched field]))
    "invalid"))

;; --- Form Specs and Conformers

(s/def ::email ::us/email)
(s/def ::not-empty-string ::us/not-empty-string)
(s/def ::color ::us/color)

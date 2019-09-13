;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.forms
  (:refer-clojure :exclude [uuid])
  (:require
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [lentes.core :as l]
   [potok.core :as ptk]
   [rumext.alpha :as mf]
   [uxbox.util.dom :as dom]
   [uxbox.util.spec :as us]
   [uxbox.util.i18n :refer [tr]]))

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

(defn- translate-error-type
  [name]
  "errors.undefined-error")

(defn- interpret-problem
  [acc {:keys [path pred val via in] :as problem}]
  ;; (prn "interpret-problem" problem)
  (cond
    (and (empty? path)
         (list? pred)
         (= (first (last pred)) 'cljs.core/contains?))
    (let [path (conj path (last (last pred)))]
      (assoc-in acc path {:name ::missing :type :builtin}))

    (and (not (empty? path))
         (not (empty? via)))
    (assoc-in acc path {:name (last via) :type :builtin})

    :else acc))

(defn use-form
  [spec initial]
  (let [[state update-state] (mf/useState {:data (if (fn? initial) (initial) initial)
                                           :errors {}
                                           :touched {}})
        clean-data (s/conform spec (:data state))
        problems (when (= ::s/invalid clean-data)
                   (::s/problems (s/explain-data spec (:data state))))


        errors (merge (reduce interpret-problem {} problems)
                      (:errors state))]
    (-> (assoc state
               :errors errors
               :clean-data (when (not= clean-data ::s/invalid) clean-data)
               :valid (and (empty? errors)
                           (not= clean-data ::s/invalid)))
        (impl-mutator update-state))))

(defn on-input-change
  [{:keys [data] :as form} field]
  (fn [event]
    (let [target (dom/get-target event)
          value (dom/get-value target)]
      (swap! form (fn [state]
                    (-> state
                        (assoc-in [:data field] value)
                        (update :errors dissoc field)))))))

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
  (let [touched? (get-in form [:touched field])
        {:keys [message code] :as error} (get-in form [:errors field])]
    (when (and touched? error
               (cond
                 (nil? type) true
                 (keyword? type) (= (:type error) type)
                 (ifn? type) (type (:type error))
                 :else false))
      (prn "field-error" error)
      [:ul.form-errors
       [:li {:key code} (tr message)]])))

(defn error-class
  [form field]
  (when (and (get-in form [:errors field])
             (get-in form [:touched field]))
    "invalid"))

;; --- Form Specs and Conformers

;; TODO: migrate to uxbox.util.spec
(s/def ::email ::us/email)
(s/def ::not-empty-string ::us/not-empty-string)
(s/def ::color ::us/color)
(s/def ::number-str ::us/number-str)

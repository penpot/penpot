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
   [uxbox.common.spec :as us]
   [uxbox.util.dom :as dom]
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

(defn use-form2
  [& {:keys [spec validators initial]}]
  (let [[state update-state] (mf/useState {:data (if (fn? initial) (initial) initial)
                                           :errors {}
                                           :touched {}})
        clean-data (s/conform spec (:data state))
        problems (when (= ::s/invalid clean-data)
                   (::s/problems (s/explain-data spec (:data state))))

        errors (merge (reduce interpret-problem {} problems)
                      (when (not= clean-data ::s/invalid)
                        (reduce (fn [errors vf]
                                  (merge errors (vf clean-data)))
                                {} validators))
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

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.forms
  (:refer-clojure :exclude [uuid])
  (:require
   [beicon.core :as rx]
   [cljs.spec.alpha :as s :include-macros true]
   [cuerdas.core :as str]
   [lentes.core :as l]
   [potok.core :as ptk]
   [rumext.alpha :as mf]
   [rumext.core :as mx]
   [struct.core :as stt]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :refer [tr]]))

;; --- Main Api

(defn validate
  [data spec]
  (stt/validate data spec))

(defn valid?
  [data spec]
  (stt/valid? data spec))

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

(defn use-form
  [{:keys [initial spec] :as opts}]
  (let [[data update-data] (mf/useState initial)
        [errors update-errors] (mf/useState nil)
        [touched update-touched] (mf/useState {})
        [errors' clean-data] (validate data spec)

        data (impl-mutator data update-data)
        errors (-> (merge {} errors' errors)
                   (impl-mutator update-errors))
        touched (impl-mutator touched update-touched)]
    {:clean-data clean-data
     :touched touched
     :data data
     :errors errors
     :valid (not (seq errors))}))

(defn on-input-change
  [{:keys [data] :as form}]
  (fn [event]
    (let [target (dom/get-target event)
          field (keyword (.-name target))
          value (dom/get-value target)]
      (swap! data assoc field value))))

(defn on-input-blur
  [{:keys [touched] :as form}]
  (fn [event]
    (let [target (dom/get-target event)
          field (keyword (.-name target))]
      (when-not (get touched field)
        (swap! touched assoc field true)))))

;; --- Additional Validators

(def non-empty-string
  {:message "errors.empty-string"
   :optional true
   :validate #(not (str/empty? %))})

(def string (assoc stt/string :message "errors.should-be-string"))
(def number (assoc stt/number :message "errors.should-be-number"))
(def number-str (assoc stt/number-str :message "errors.should-be-number"))
(def integer (assoc stt/integer :message "errors.should-be-integer"))
(def integer-str (assoc stt/integer-str :message "errors.should-be-integer"))
(def required (assoc stt/required :message "errors.required"))
(def email (assoc stt/email :message "errors.should-be-valid-email"))
(def uuid (assoc stt/uuid :message "errors.should-be-uuid"))
(def uuid-str (assoc stt/uuid-str :message "errors.should-be-valid-uuid"))

;; DEPRECATED

;; --- Form Validation Api

(defn- interpret-problem
  [acc {:keys [path pred val via in] :as problem}]
  (cond
    (and (empty? path)
         (= (first pred) 'contains?))
    (let [path (conj path (last pred))]
      (update-in acc path assoc :missing))

    (and (seq path)
         (= 1 (count path)))
    (update-in acc path assoc :invalid)

    :else acc))

;; (defn validate
;;   [spec data]
;;   (when-not (s/valid? spec data)
;;     (let [report (s/explain-data spec data)]
;;       (reduce interpret-problem {} (::s/problems report)))))

;; (defn valid?
;;   [spec data]
;;   (s/valid? spec data))


;; --- Form Specs and Conformers

(def ^:private email-re
  #"^[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+$")

(def ^:private number-re
  #"^[-+]?[0-9]*\.?[0-9]+$")

(def ^:private color-re
  #"^#[0-9A-Fa-f]{6}$")

(s/def ::email
  (s/and string? #(boolean (re-matches email-re %))))

(s/def ::non-empty-string
  (s/and string? #(not (str/empty? %))))

(defn- parse-number
  [v]
  (cond
    (re-matches number-re v) (js/parseFloat v)
    (number? v) v
    :else ::s/invalid))

(s/def ::string-number
  (s/conformer parse-number str))

(s/def ::color
  (s/and string? #(boolean (re-matches color-re %))))

;; --- Form State Events

;; --- Assoc Error

(defrecord AssocError [type field error]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:errors type field] error)))

(defn assoc-error
  ([type field]
   (assoc-error type field nil))
  ([type field error]
   {:pre [(keyword? type)
          (keyword? field)
          (any? error)]}
   (AssocError. type field error)))

;; --- Assoc Errors

(defrecord AssocErrors [type errors]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:errors type] errors)))

(defn assoc-errors
  ([type]
   (assoc-errors type nil))
  ([type errors]
   {:pre [(keyword? type)
          (or (map? errors)
              (nil? errors))]}
   (AssocErrors. type errors)))

;; --- Assoc Value

(declare clear-error)

(defrecord AssocValue [type field value]
  ptk/UpdateEvent
  (update [_ state]
    (let [form-path (into [:forms type] (if (coll? field) field [field]))]
      (assoc-in state form-path value)))

  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (clear-error type field))))

(defn assoc-value
  [type field value]
  {:pre [(keyword? type)
         (keyword? field)
         (any? value)]}
  (AssocValue. type field value))

;; --- Clear Values

(defrecord ClearValues [type]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:forms type] nil)))

(defn clear-values
  [type]
  {:pre [(keyword? type)]}
  (ClearValues. type))

;; --- Clear Error

(deftype ClearError [type field]
  ptk/UpdateEvent
  (update [_ state]
    (let [errors (get-in state [:errors type])]
      (if (map? errors)
        (assoc-in state [:errors type] (dissoc errors field))
        (update state :errors dissoc type)))))

(defn clear-error
  [type field]
  {:pre [(keyword? type)
         (keyword? field)]}
  (ClearError. type field))

;; --- Clear Errors

(defrecord ClearErrors [type]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:errors type] nil)))

(defn clear-errors
  [type]
  {:pre [(keyword? type)]}
  (ClearErrors. type))

;; --- Clear Form

(deftype ClearForm [type]
  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (clear-values type)
           (clear-errors type))))

(defn clear-form
  [type]
  {:pre [(keyword? type)]}
  (ClearForm. type))

;; --- Helpers

(defn focus-data
  [type state]
  (-> (l/in [:forms type])
      (l/derive state)))

(defn focus-errors
  [type state]
  (-> (l/in [:errors type])
      (l/derive state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Form UI
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mx/defc input-error
  [errors field]
  (when-let [error (get errors field)]
    [:ul.form-errors
     [:li {:key error} (tr error)]]))

(defn error-class
  [errors field]
  (when (get errors field)
    "invalid"))

(defn clear-mixin
  [store type]
  {:will-unmount (fn [own]
                   (ptk/emit! store (clear-form type))
                   own)})

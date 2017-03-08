;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.forms
  (:require [cljs.spec :as s :include-macros true]
            [cuerdas.core :as str]
            [lentes.core :as l]
            [beicon.core :as rx]
            [potok.core :as ptk]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.i18n :refer [tr]]))

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

(defn validate
  [spec data]
  (when-not (s/valid? spec data)
    (let [report (s/explain-data spec data)]
      (reduce interpret-problem {} (::s/problems report)))))

(defn valid?
  [spec data]
  (s/valid? spec data))

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

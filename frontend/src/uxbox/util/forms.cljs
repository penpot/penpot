;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.util.forms
  (:refer-clojure :exclude [keyword uuid vector boolean map set])
  (:require [struct.core :as f]
            [lentes.core :as l]
            [beicon.core :as rx]
            [potok.core :as ptk]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.i18n :refer (tr)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Form Validation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Form Validators

(def required
  (assoc f/required :message "errors.form.required"))

(def string
  (assoc f/string :message "errors.form.string"))

(def number
  (assoc f/number :message "errors.form.number"))

(def integer
  (assoc f/integer :message "errors.form.integer"))

(def boolean
  (assoc f/boolean :message "errors.form.bool"))

(def identical-to
  (assoc f/identical-to :message "errors.form.identical-to"))

(def in-range f/in-range)
(def uuid f/uuid)
(def keyword f/keyword)
(def integer-str f/integer-str)
(def number-str f/number-str)
(def email f/email)
(def positive f/positive)

(def max-len
  {:message "errors.form.max-len"
   :optional true
   :validate (fn [v n]
               (let [len (count v)]
                 (>= len v)))})

(def min-len
  {:message "errors.form.min-len"
   :optional true
   :validate (fn [v n]
               (>= (count v) n))})

(def color
  {:message "errors.form.color"
   :optional true
   :validate #(not (nil? (re-find #"^#[0-9A-Fa-f]{6}$" %)))})

;; --- Public Validation Api

(defn validate
  ([data schema]
   (validate data schema nil))
  ([data schema opts]
   (f/validate data schema opts)))

(defn validate!
  ([data schema]
   (validate! data schema nil))
  ([data schema opts]
   (let [[errors data] (validate data schema opts)]
     (if errors
       (throw (ex-info "Invalid data" errors))
       data))))

(defn valid?
  [data schema]
  (let [[errors data] (validate data schema)]
    (not errors)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Form Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Set Error

(defrecord SetError [type field error]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:errors type field] error)))

(defn set-error
  ([type field]
   (set-error type field nil))
  ([type field error]
   {:pre [(keyword? type)
          (keyword? field)
          (any? error)]}
   (SetError. type field error)))

(defn set-error!
  [store & args]
  (ptk/emit! store (apply set-error args)))

;; --- Set Errors

(defrecord SetErrors [type errors]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:errors type] errors)))

(defn set-errors
  ([type]
   (set-errors type nil))
  ([type errors]
   {:pre [(keyword? type)
          (or (map? errors)
              (nil? errors))]}
   (SetErrors. type errors)))

(defn set-errors!
  [store & args]
  (ptk/emit! store (apply set-errors args)))

;; --- Set Value

(defrecord SetValue [type field value]
  ptk/UpdateEvent
  (update [_ state]
    (let [form-path (into [:forms type] (if (coll? field) field [field]))
          errors-path (into [:errors type] (if (coll? field) field [field]))]
      (-> state
          (assoc-in form-path value)
          (update-in (butlast errors-path) dissoc (last errors-path))))))

(defn set-value
  [type field value]
  {:pre [(keyword? type)
         (keyword? field)
         (any? value)]}
  (SetValue. type field value))

(defn set-value!
  [store type field value]
  (ptk/emit! store (set-value type field value)))

;; --- Clear Form

(defrecord ClearForm [type]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:forms type] nil)))

(defn clear-form
  [type]
  {:pre [(keyword? type)]}
  (ClearForm. type))

(defn clear-form!
  [store type]
  (ptk/emit! store (clear-form type)))

;; --- Clear Errors

(defrecord ClearErrors [type]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:errors type] nil)))

(defn clear-errors
  [type]
  {:pre [(keyword? type)]}
  (ClearErrors. type))

(defn clear-errors!
  [store type]
  (ptk/emit! store (clear-errors type)))

;; --- Clear

(defn clear!
  [store type]
  (ptk/emit! store
             (clear-form type)
             (clear-errors type)))

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
                   (clear! store type)
                   own)})

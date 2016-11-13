;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.util.forms
  (:refer-clojure :exclude [keyword uuid vector boolean map set])
  (:require [struct.core :as st]
            [lentes.core :as l]
            [beicon.core :as rx]
            [uxbox.util.rstore :as rs]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.i18n :refer (tr)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Form Validation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Form Validators

(def required
  (assoc st/required :message "errors.form.required"))

(def string
  (assoc st/string :message "errors.form.string"))

(def number
  (assoc st/number :message "errors.form.number"))

(def integer
  (assoc st/integer :message "errors.form.integer"))

(def boolean
  (assoc st/boolean :message "errors.form.bool"))

(def identical-to
  (assoc st/identical-to :message "errors.form.identical-to"))

(def in-range st/in-range)
;; (def uuid-like st/uuid-like)
(def uuid st/uuid)
(def keyword st/keyword)
(def integer-str st/integer-str)
(def number-str st/number-str)
;; (def boolean-like st/boolean-like)
(def email st/email)
;; (def function st/function)
(def positive st/positive)
;; (def validate st/validate)
;; (def validate! st/validate!)

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
   (st/validate data schema opts)))

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
  rs/UpdateEvent
  (-apply-update [_ state]
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
  [& args]
  (rs/emit! (apply set-error args)))

;; --- Set Errors

(defrecord SetErrors [type errors]
  rs/UpdateEvent
  (-apply-update [_ state]
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
  [& args]
  (rs/emit! (apply set-errors args)))

;; --- Set Value

(defrecord SetValue [type field value]
  rs/UpdateEvent
  (-apply-update [_ state]
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
  [type field value]
  (rs/emit! (set-value type field value)))

;; --- Validate Form

;; (defrecord ValidateForm [type form data on-success]
;;   rs/WatchEvent
;;   (-apply-watch [_ state stream]
;;     (let [[errors data] (validate data form)]
;;       (if errors
;;         (rx/of (set-errors type errors))
;;         (do
;;           (on-success data)
;;           (rx/empty))))))

;; (defn validate-form
;;   [& {:keys [type form data on-success]}]
;;   {:pre [(keyword? type)
;;          (map? form)
;;          (map? data)
;;          (fn? on-success)]}
;;   (ValidateForm. type form data on-success))

;; (defn validate-form!
;;   [& args]
;;   (rs/emit! (apply validate-form args)))

;; --- Clear Form

(defrecord ClearForm [type]
  rs/UpdateEvent
  (-apply-update [_ state]
    (assoc-in state [:forms type] nil)))

(defn clear-form
  [type]
  {:pre [(keyword? type)]}
  (ClearForm. type))

(defn clear-form!
  [type]
  (rs/emit! (clear-form type)))

;; --- Clear Form

(defrecord ClearErrors [type]
  rs/UpdateEvent
  (-apply-update [_ state]
    (assoc-in state [:errors type] nil)))

(defn clear-errors
  [type]
  {:pre [(keyword? type)]}
  (ClearErrors. type))

(defn clear-errors!
  [type]
  (rs/emit! (clear-errors type)))

;; --- Clear

(defrecord Clear [type]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (rx/of (clear-form type)
           (clear-errors type))))

(defn clear
  [type]
  (Clear. type))

(defn clear!
  [type]
  (rs/emit! (clear type)))

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

(defn cleaner-fn
  [type]
  {:pre [(keyword? type)]}
  (fn [own]
    (clear! type)
    own))

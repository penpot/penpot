;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.forms
  (:require [beicon.core :as rx]
            [promesa.core :as p]
            [lentes.core :as l]
            [uxbox.main.repo :as rp]
            [uxbox.common.rstore :as rs]
            [uxbox.main.state :as st]
            [uxbox.common.schema :as sc]
            [uxbox.common.i18n :refer (tr)]))

;; --- Assign Errors

(defrecord AssignErrors [type errors]
  rs/UpdateEvent
  (-apply-update [_ state]
    (assoc-in state [:errors type] errors)))

(defn assign-errors
  ([type] (assign-errors type nil))
  ([type errors]
   (AssignErrors. type errors)))

;; --- Assign Field Value

(defrecord AssignFieldValue [type field value]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [form-path (into [:forms type] (if (coll? field) field [field]))
          errors-path (into [:errors type] (if (coll? field) field [field]))]
      (-> state
          (assoc-in form-path value)
          (update-in (butlast errors-path) dissoc (last errors-path))))))

(defn assign-field-value
  [type field value]
  (AssignFieldValue. type field value))

;; --- Clean Errors

(defrecord CleanErrors [type]
  rs/UpdateEvent
  (-apply-update [_ state]
    (assoc-in state [:errors type] nil)))

(defn clean-errors
  [type]
  (CleanErrors. type))

;; --- Clean Form

(defrecord CleanForm [type]
  rs/UpdateEvent
  (-apply-update [_ state]
    (assoc-in state [:forms type] nil)))

(defn clean-form
  [type]
  (CleanForm. type))

;; --- Clean

(defrecord Clean [type]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (rx/of (clean-form type)
           (clean-errors type))))

(defn clean
  [type]
  (Clean. type))

;; --- Helpers

(defn focus-form-data
  [type]
  (-> (l/in [:forms type])
      (l/derive st/state)))

(defn focus-form-errors
  [type]
  (-> (l/in [:errors type])
      (l/derive st/state)))



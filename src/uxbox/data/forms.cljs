;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.data.forms
  (:require [beicon.core :as rx]
            [promesa.core :as p]
            [uxbox.repo :as rp]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.schema :as sc]
            [uxbox.locales :refer (tr)]
            [uxbox.ui.messages :as uum]))

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
    (assoc-in state [:forms type field] value)))

(defn assign-field-value
  [type field value]
  (AssignFieldValue. type field value))



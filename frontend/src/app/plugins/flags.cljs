;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.plugins.flags
  (:require
   [app.common.data :as d]
   [app.main.store :as st]
   [app.plugins.utils :as u]
   [app.util.object :as obj]
   [potok.v2.core :as ptk]))

(defn initialize
  "Initialize flags values for plugins"
  [id version]
  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (let [version (d/nilv version 1)]
        (update-in state [:plugins :flags] assoc id
                   {:natural-child-ordering false
                    ;; For version >= 2 harden the contract by throwing errors
                    ;; on validation failures
                    :throw-validation-errors (>= version 2)})))))

(defn- set-flag
  [id key value]
  (ptk/reify ::set-flag
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:plugins :flags id] assoc key value))))

(defn flags-proxy
  [plugin-id]
  (obj/reify {:name "FlagProxy"}
    :naturalChildOrdering
    {:this false
     :get
     (fn [] (u/natural-child-ordering? plugin-id))

     :set
     (fn [value]
       (cond
         (not (boolean? value))
         (u/not-valid plugin-id :naturalChildOrdering value)

         :else
         (st/emit! (set-flag plugin-id :natural-child-ordering value))))}

    :throwValidationErrors
    {:this false
     :get
     (fn [] (u/throw-validation-errors? plugin-id))

     :set
     (fn [value]
       (cond
         (not (boolean? value))
         (u/not-valid plugin-id :throwValidationErrors value)

         :else
         (st/emit! (set-flag plugin-id :throw-validation-errors value))))}))

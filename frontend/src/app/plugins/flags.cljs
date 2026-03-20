;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.flags
  (:require
   [app.common.data.macros :as dm]
   [app.main.store :as st]
   [app.plugins.utils :as u]
   [app.util.object :as obj]
   [potok.v2.core :as ptk]))

(defn natural-child-ordering?
  [plugin-id]
  (boolean
   (dm/get-in @st/state [:plugins :flags plugin-id :natural-child-ordering])))

(defn clear
  [id]
  (ptk/reify ::reset
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:plugins :flags] assoc id {}))))

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
     (fn [] (natural-child-ordering? plugin-id))

     :set
     (fn [value]
       (cond
         (not (boolean? value))
         (u/display-not-valid :naturalChildOrdering value)

         :else
         (st/emit! (set-flag plugin-id :natural-child-ordering value))))}))

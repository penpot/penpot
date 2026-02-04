;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.flags
  (:require
   [app.main.data.plugins :as dp]
   [app.main.store :as st]
   [app.plugins.utils :as u]
   [app.util.object :as obj]))

(defn flags-proxy
  [plugin-id]
  (obj/reify {:name "FlagProxy"}
    :naturalChildOrdering
    {:this false
     :get
     (fn []
       (boolean
        (get-in
         @st/state
         [:workspace-local :plugin-flags plugin-id :natural-child-ordering])))

     :set
     (fn [value]
       (cond
         (not (boolean? value))
         (u/display-not-valid :naturalChildOrdering value)

         :else
         (st/emit! (dp/set-plugin-flag plugin-id :natural-child-ordering value))))}))

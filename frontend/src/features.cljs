;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

;; This namespace is only to export the functions for toggle features
(ns features
  (:require
   [app.main.features :as features]
   [app.main.store :as st]
   [app.plugins :as plugins]
   [app.util.timers :as tm]))

(defn ^:export grid []
  (tm/schedule-on-idle #(st/emit! (features/toggle-feature "layout/grid")))
  nil)

(defn ^:export get-enabled []
  (clj->js features/global-enabled-features))

(defn ^:export get-team-enabled []
  (clj->js (get @st/state :features)))

(defn ^:export plugins []
  (st/emit! (features/enable-feature "plugins/runtime"))
  (plugins/init-plugins-runtime!)
  nil)

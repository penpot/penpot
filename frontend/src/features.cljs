;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

;; This namespace is only to export the functions for toggle features
(ns features
  (:require
   [app.main.features :as feat]
   [app.main.store :as st]
   [app.plugins :as plugins]
   [app.util.timers :as tm]))

(defn ^:export grid []
  (tm/schedule-on-idle #(st/emit! (feat/toggle-feature "layout/grid")))
  nil)

(defn ^:export get-enabled []
  (clj->js feat/global-enabled-features))

(defn ^:export get-team-enabled []
  (clj->js (get @st/state :features)))

(defn ^:export plugins []
  (st/emit! (feat/enable-feature "plugins/runtime"))
  (plugins/init-plugins-runtime)
  nil)

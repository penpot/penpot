;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

;; This namespace is only to export the functions for toggle features
(ns features
  (:require
   [app.main.features :as features]))

(defn ^:export autolayout []
  (features/toggle-feature! :auto-layout))

(defn ^:export components-v2 []
  (features/toggle-feature! :components-v2))


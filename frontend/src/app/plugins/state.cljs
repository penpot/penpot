;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.state
  (:require
   [app.main.store :as st]))

(defn natural-child-ordering?
  [plugin-id]
  (boolean
   (get-in
    @st/state
    [:workspace-local :plugin-flags plugin-id :natural-child-ordering])))

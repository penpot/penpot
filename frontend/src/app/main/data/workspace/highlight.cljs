;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.highlight
  (:require
   [app.common.spec :as us]
   [potok.core :as ptk]))

;; --- Manage shape's highlight status

(defn highlight-shape
  [id]
  (us/verify ::us/uuid id)
  (ptk/reify ::highlight-shape
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :highlighted] clojure.set/union #{id}))))

(defn dehighlight-shape
  [id]
  (us/verify ::us/uuid id)
  (ptk/reify ::dehighlight-shape
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :highlighted] disj id))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.history
  (:require
   [app.main.data.workspace.layout :as dwl]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn initialize-history
  "Toggle the History panel. The panel closes itself on Escape (see
  `history-content*`), so that an open menu inside it can take the
  first Escape."
  []
  (ptk/reify ::initialize-history
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dwl/toggle-layout-flag :document-history)))))

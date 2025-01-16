;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.history
  (:require
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.layout :as dwl]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))


(defn initialize-history
  []
  (ptk/reify ::initialize-history
    ptk/WatchEvent
    (watch [_ _ stream]
      (let [clear-history-mode #(dwl/remove-layout-flag :document-history)]
        (rx/merge
         (rx/of (dwl/toggle-layout-flag :document-history))
         (->> stream
              (rx/filter dwc/interrupt?)
              (rx/take 1)
              (rx/map clear-history-mode)))))))

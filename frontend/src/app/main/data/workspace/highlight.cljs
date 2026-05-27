;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.highlight
  (:require
   [app.common.data.macros :as dm]
   [clojure.set :as set]
   [potok.v2.core :as ptk]))

;; --- Manage shape's highlight status

(defn highlight-shape
  [id]
  (dm/assert! (uuid? id))
  (ptk/reify ::highlight-shape
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :highlighted] set/union #{id}))))

(defn dehighlight-shape
  [id]
  (dm/assert! (uuid? id))
  (ptk/reify ::dehighlight-shape
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :highlighted] disj id))))

(defn set-search-match-highlight
  "Highlight the active find/replace match on canvas and sidebar."
  [current-id match-ids]
  (dm/assert! (uuid? current-id))
  (let [match-ids (set match-ids)]
    (ptk/reify ::set-search-match-highlight
      ptk/UpdateEvent
      (update [_ state]
        (let [highlighted (-> (get-in state [:workspace-local :highlighted] #{})
                              (set/difference match-ids)
                              (conj current-id))]
          (-> state
              (assoc-in [:workspace-local :search-match-highlight] current-id)
              (assoc-in [:workspace-local :highlighted] highlighted)))))))

(defn clear-search-match-highlight
  [match-ids]
  (let [match-ids (set match-ids)]
    (ptk/reify ::clear-search-match-highlight
      ptk/UpdateEvent
      (update [_ state]
        (-> state
            (update-in [:workspace-local :highlighted]
                       #(set/difference (or % #{}) match-ids))
            (update :workspace-local dissoc :search-match-highlight))))))

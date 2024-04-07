;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.edition
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.workspace.path.common :as dwpc]
   [app.main.data.workspace.state-helpers :as wsh]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn interrupt? [e] (= e :interrupt))

(declare clear-edition-mode)

(defn start-edition-mode
  [id]
  (dm/assert! (uuid? id))
  (ptk/reify ::start-edition-mode
    ptk/UpdateEvent
    (update [_ state]
      (let [objects (wsh/lookup-page-objects state)]
        ;; Can only edit objects that exist
        (if (contains? objects id)
          (-> state
              (assoc-in [:workspace-local :selected] #{id})
              (assoc-in [:workspace-local :edition] id)
              (dissoc :workspace-grid-edition))
          state)))

    ptk/WatchEvent
    (watch [_ _ stream]
      (->> stream
           (rx/filter interrupt?)
           (rx/take 1)
           (rx/map clear-edition-mode)))))

;; If these event change modules review /src/app/main/data/workspace/path/undo.cljs
(defn clear-edition-mode
  []
  (ptk/reify ::clear-edition-mode
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-local dissoc :edition)
          (update :workspace-drawing dissoc :tool :object :lock)
          (dissoc :workspace-grid-edition)))

    ptk/WatchEvent
    (watch [_ state _]
      (let [id (get-in state [:workspace-local :edition])]
        (rx/concat
         (when (some? id)
           (dwpc/finish-path)))))))


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.edition
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.path.common :as dwpc]
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
      (let [objects (dsh/lookup-page-objects state)]
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

;; IMPORTANT: If this event is moved from this namespace to other,
;; update namespace reference in the
;; app/main/data/workspace/path/undo.cljs file.

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


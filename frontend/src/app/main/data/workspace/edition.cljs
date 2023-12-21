;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.edition
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace.common :as dwc]
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
    (watch [_ state stream]
      (let [objects (wsh/lookup-page-objects state)]
        (rx/concat
         (if (ctl/grid-layout? objects id)
           (rx/of (dwc/hide-toolbar))
           (rx/empty))
         (->> stream
              (rx/filter interrupt?)
              (rx/take 1)
              (rx/map (constantly clear-edition-mode))))))))

;; If these event change modules review /src/app/main/data/workspace/path/undo.cljs
(def clear-edition-mode
  (ptk/reify ::clear-edition-mode
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])]
        (-> state
            (update :workspace-local dissoc :edition)
            (dissoc :workspace-grid-edition)
            (assoc-in  [:workspace-local :hide-toolbar] false)
            (cond-> (some? id) (update-in [:workspace-local :edit-path] dissoc id)))))))

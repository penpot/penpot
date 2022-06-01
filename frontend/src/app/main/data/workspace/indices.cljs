;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.indices
  (:require
   [app.main.data.workspace.changes :as dwc]
   [app.main.data.workspace.indices.object-tree :as dwi-object-tree]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.refs :as refs]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(def stop-indexing? (ptk/type? ::stop-indexing))

(def objects-changes #{:add-obj :mod-obj :del-obj :mov-objects})

(defn stop-indexing
  []
  (ptk/reify ::stop-indexing
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (dissoc :index-object-tree)))))

(defn process-changes
  "Simplify changes so we have only the type of operation and the ids"
  [changes]
  (->> changes
       (filter #(contains? objects-changes (:type %)))
       (mapcat (fn [{:keys [type id shapes]}]
                 (if (some? shapes)
                   (->> shapes (map #(vector type %)))
                   [[type id]])))))

(defn update-indexing
  [change-type shape-id old-objects new-objects]
  (ptk/reify ::update-indexing
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :index-object-tree dwi-object-tree/update-index shape-id change-type old-objects new-objects)))))

(defn start-indexing
  []
  (ptk/reify ::start-indexing
    ptk/UpdateEvent
    (update [_ state]
      (let [objects (wsh/lookup-page-objects state)]
        (-> state
            (assoc :index-object-tree (dwi-object-tree/init-index objects)))))

    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stopper (->> stream (rx/filter stop-indexing?) (rx/take 1))
            objects-delta (->> (rx/from-atom refs/workspace-page-objects {:emit-current-value? true}) (rx/buffer 2 1))]
        (->> stream
             (rx/filter dwc/commit-changes?)
             (rx/flat-map #(->> % deref :changes process-changes))
             (rx/with-latest-from objects-delta)
             (rx/map (fn [[[type id] [objects-old objects-new]]]
                       (update-indexing type id objects-old objects-new)))
             #_(rx/tap (fn [[[type id] [objects-old objects-new]]]
                       (let [obj-old (get objects-old id)
                             obj-new (get objects-new id)]
                         (prn ">change" (or (:name obj-old) (:name obj-new)))
                         (prn "  > " type)
                         (.log js/console "  >" (clj->js obj-old))
                         (.log js/console "  >" (clj->js obj-new))

                         )))
             (rx/take-until stopper)
             (rx/ignore))))))

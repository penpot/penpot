;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.fix-broken-shape-links
  (:require
   [app.main.data.workspace.changes :as dch]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defn- generate-changes
  [attr {:keys [objects id]}]
  (let [base      {:type :fix-obj attr id}
        contains? (partial contains? objects)
        xform     (comp
                   (remove #(every? contains? (:shapes %)))
                   (map #(assoc base :id (:id %))))]
    (sequence xform (vals objects))))

(defn fix-broken-shapes
  []
  (ptk/reify ::fix-broken-shape-links
    ptk/WatchEvent
    (watch [it state _]
      (let [data    (get state :workspace-data)
            changes (concat
                     (mapcat (partial generate-changes :page-id)
                             (vals (:pages-index data)))
                     (mapcat (partial generate-changes :component-id)
                             (vals (:components data))))]

        (rx/of (dch/commit-changes
                {:origin it
                 :redo-changes (vec changes)
                 :undo-changes []
                 :save-undo? false}))))))

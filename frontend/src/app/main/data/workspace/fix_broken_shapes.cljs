;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.fix-broken-shapes
  (:require
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn- generate-broken-link-changes
  [attr {:keys [objects id] :as container}]
  (let [base      {:type :fix-obj :fix :broken-children attr id}
        contains? (partial contains? objects)
        xform     (comp
                   ;; FIXME: Ensure all obj have id field (this is needed
                   ;; because some bug adds an ephimeral shape with id ZERO,
                   ;; with a single attr `:shapes` having a vector of ids
                   ;; pointing to not existing shapes). That happens on
                   ;; components. THIS IS A WORKAOURD
                   (map (fn [[id obj]]
                          (if (some? (:id obj))
                            obj
                            (assoc obj :id id))))

                   ;; Remove all valid shapes
                   (remove (fn [obj]
                             (every? contains? (:shapes obj))))

                   (map (fn [obj]
                          (assoc base :id (:id obj)))))]

    (sequence xform objects)))

(defn fix-broken-shapes
  []
  (ptk/reify ::fix-broken-shapes
    ptk/WatchEvent
    (watch [it state _]
      (let [fdata   (dsh/lookup-file-data state)
            changes (concat
                     (mapcat (partial generate-broken-link-changes :page-id)
                             (vals (:pages-index fdata)))
                     (mapcat (partial generate-broken-link-changes :component-id)
                             (vals (:components fdata))))]

        (if (seq changes)
          (rx/of (dch/commit-changes
                  {:origin it
                   :redo-changes (vec changes)
                   :undo-changes []
                   :save-undo? false}))
          (rx/empty))))))

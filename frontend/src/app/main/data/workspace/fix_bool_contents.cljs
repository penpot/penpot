;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.fix-bool-contents
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.state-helpers :as wsh]
   [beicon.core :as rx]
   [potok.core :as ptk]))

;; This event will update the file so the boolean data has a pre-generated path data
;; to increase performance.
;; For new shapes this will be generated in the :reg-objects but we need to do this for
;; old files.

;; FIXME: Remove me after June 2022

(defn fix-bool-contents
  "This event will calculate the bool content and update the page. This is kind of a 'addhoc' migration
  to fill the optional value 'bool-content'"
  []

  (letfn [(should-migrate-shape? [shape]
            (and (= :bool (:type shape)) (not (contains? shape :bool-content))))

          (should-migrate-component? [component]
            (->> (:objects component)
                 (vals)
                 (d/seek should-migrate-shape?)))

          (update-shape [shape objects]
            (cond-> shape
              (should-migrate-shape? shape)
              (assoc :bool-content (gsh/calc-bool-content shape objects))))

          (migrate-component [component]
            (-> component
                (update
                 :objects
                 (fn [objects]
                   (d/mapm #(update-shape %2 objects) objects)))))

          (update-library
            [library]
            (-> library
                (d/update-in-when
                 [:data :components]
                 (fn [components]
                   (d/mapm #(migrate-component %2) components)))))]

    (ptk/reify ::fix-bool-contents
      ptk/UpdateEvent
      (update [_ state]
        ;; Update (only-local) the imported libraries
        (-> state
            (d/update-when
             :workspace-libraries
             (fn [libraries] (d/mapm #(update-library %2) libraries)))))

      ptk/WatchEvent
      (watch [it state _]
        (let [objects (wsh/lookup-page-objects state)

              ids (into #{}
                        (comp (filter should-migrate-shape?) (map :id))
                        (vals objects))

              components (->> (wsh/lookup-local-components state)
                              (vals)
                              (filter should-migrate-component?))

              component-changes
              (into []
                    (map (fn [component]
                           {:type :mod-component
                            :id (:id component)
                            :objects (-> component migrate-component :objects)}))
                    components)]

          (rx/of (dch/update-shapes ids #(update-shape % objects) {:reg-objects? false
                                                                   :save-undo? false
                                                                   :ignore-tree true}))

          (if (empty? component-changes)
            (rx/empty)
            (rx/of (dch/commit-changes {:origin it
                                        :redo-changes component-changes
                                        :undo-changes []
                                        :save-undo? false}))))))))

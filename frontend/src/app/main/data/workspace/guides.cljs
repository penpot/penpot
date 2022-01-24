;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.guides
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.spec :as us]
   [app.common.types.page-options :as tpo]
   [app.main.data.workspace.changes :as dwc]
   [app.main.data.workspace.state-helpers :as wsh]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]))

(defn make-update-guide [guide]
  (fn [other]
    (cond-> other
      (= (:id other) (:id guide))
      (merge guide))))

(defn update-guides [guide]
  (us/verify ::tpo/guide guide)
  (ptk/reify ::update-guides
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            guides (-> state wsh/lookup-page-options (:guides {}))

            new-guides (assoc guides (:id guide) guide)
            
            rch [{:type :set-option
                  :page-id page-id
                  :option :guides
                  :value new-guides}]
            uch [{:type :set-option
                  :page-id page-id
                  :option :guides
                  :value guides}]]
        (rx/of
         (dwc/commit-changes
          {:redo-changes rch
           :undo-changes uch
           :origin it}))))))

(defn remove-guide [guide]
  (us/verify ::tpo/guide guide)
  (ptk/reify ::remove-guide
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            guides (-> state wsh/lookup-page-options (:guides {}))
            new-guides (dissoc guides (:id guide))
            
            rch [{:type :set-option
                  :page-id page-id
                  :option :guides
                  :value new-guides}]
            uch [{:type :set-option
                  :page-id page-id
                  :option :guides
                  :value guides}]]
        (rx/of
         (dwc/commit-changes
          {:redo-changes rch
           :undo-changes uch
           :origin it}))))))

(defn move-frame-guides
  [ids]
  (us/verify (s/coll-of uuid?) ids)

  (ptk/reify ::move-frame-guides
    ptk/WatchEvent

    (watch [_ state _]
      (let [objects (wsh/lookup-page-objects state)
            frame-ids? (->> ids (filter #(= :frame (get-in objects [% :type]))) (into #{}))
            object-modifiers  (get state :workspace-modifiers)

            build-move-event
            (fn [guide]
              (let [frame (get objects (:frame-id guide))
                    frame' (-> (merge frame (get object-modifiers (:frame-id guide)))
                               (gsh/transform-shape))

                    moved (gpt/to-vec (gpt/point (:x frame) (:y frame))
                                      (gpt/point (:x frame') (:y frame')))

                    guide (update guide :position + (get moved (:axis guide)))]
                (update-guides guide)))]

        (->> (wsh/lookup-page-options state)
             :guides
             (vals)
             (filter (comp frame-ids? :frame-id))
             (map build-move-event)
             (rx/from))))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.guides
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.types.page :as ctp]
   [app.main.data.changes :as dwc]
   [app.main.data.events :as ev]
   [app.main.data.workspace.state-helpers :as wsh]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn make-update-guide
  [guide]
  (fn [other]
    (cond-> other
      (= (:id other) (:id guide))
      (merge guide))))

(defn update-guides
  [guide]
  (dm/assert!
   "expected valid guide"
   (ctp/check-page-guide! guide))

  (ptk/reify ::update-guides
    ev/Event
    (-data [_]
      (assoc guide ::ev/name "update-guide"))

    ptk/WatchEvent
    (watch [it state _]
      (let [page (wsh/lookup-page state)
            changes
            (-> (pcb/empty-changes it)
                (pcb/with-page page)
                (pcb/update-page-option :guides assoc (:id guide) guide))]
        (rx/of (dwc/commit-changes changes))))))

(defn remove-guide
  [guide]
  (dm/assert!
   "expected valid guide"
   (ctp/check-page-guide! guide))

  (ptk/reify ::remove-guide
    ev/Event
    (-data [_] guide)

    ptk/UpdateEvent
    (update [_ state]
      (let [sdisj (fnil disj #{})]
        (update-in state [:workspace-guides :hover] sdisj (:id guide))))

    ptk/WatchEvent
    (watch [it state _]
      (let [page (wsh/lookup-page state)
            changes
            (-> (pcb/empty-changes it)
                (pcb/with-page page)
                (pcb/update-page-option :guides dissoc (:id guide)))]
        (rx/of (dwc/commit-changes changes))))))

(defn remove-guides
  [ids]
  (ptk/reify ::remove-guides
    ptk/WatchEvent
    (watch [_ state _]
      (let [page       (wsh/lookup-page state)
            guides     (get-in page [:options :guides] {})
            guides (-> (select-keys guides ids) (vals))]
        (rx/from (->> guides (mapv #(remove-guide %))))))))

(defmethod ptk/resolve ::move-frame-guides
  [_ args]
  (dm/assert!
   "expected a coll of uuids"
   (every? uuid? (:ids args)))
  (ptk/reify ::move-frame-guides
    ptk/WatchEvent
    (watch [_ state _]
      (let [ids (:ids args)
            object-modifiers (:modifiers args)

            objects (wsh/lookup-page-objects state)

            is-frame? (fn [id] (= :frame (get-in objects [id :type])))
            frame-ids? (into #{} (filter is-frame?) ids)

            build-move-event
            (fn [guide]
              (let [frame (get objects (:frame-id guide))
                    frame' (gsh/transform-shape frame (get-in object-modifiers [(:frame-id guide) :modifiers]))

                    moved (gpt/to-vec (gpt/point (:x frame) (:y frame))
                                      (gpt/point (:x frame') (:y frame')))

                    guide (update guide :position + (get moved (:axis guide)))]
                (update-guides guide)))

            guides (-> state wsh/lookup-page-options :guides vals)]

        (->> guides
             (filter (comp frame-ids? :frame-id))
             (map build-move-event)
             (rx/from))))))

(defn set-hover-guide
  [id hover?]
  (ptk/reify ::set-hover-guide
    ptk/UpdateEvent
    (update [_ state]
      (let [sconj (fnil conj #{})
            sdisj (fnil disj #{})]
        (if hover?
          (update-in state [:workspace-guides :hover] sconj id)
          (update-in state [:workspace-guides :hover] sdisj id))))))

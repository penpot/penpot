;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.guides
  (:require
   [app.common.spec :as us]
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
  ;; TODO CHECK SPEC
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
  ;; TODO CHECK SPEC
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
            frame-ids (->> ids (filter #(= :frame (get-in objects [% :type]))) (into #{}))
            object-modifiers  (get state :workspace-modifiers)

            moved-guide?
            (fn [guide]
              (let [frame-id (:frame-id guide)]
                (and (contains? frame-ids frame-id)
                     (some? (get-in object-modifiers [frame-id :modifiers :displacement])))))

            build-move-event
            (fn [guide]
              (let [disp (get-in object-modifiers [(:frame-id guide) :modifiers :displacement])
                    guide (if (= :x (:axis guide))
                            (update guide :position + (:e disp))
                            (update guide :position + (:f disp)))]
                (update-guides guide)))]

        (->> (wsh/lookup-page-options state)
             :guides
             (vals)
             (filter moved-guide?)
             (map build-move-event)
             (rx/from))))))

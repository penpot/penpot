;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.thumbnails
  (:require
   [app.common.data :as d]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as dch]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defn force-render-stream [id]
  (->> st/stream
       (rx/filter (ptk/type? ::force-render))
       (rx/map deref)
       (rx/filter #(= % id))
       (rx/take 1)))

(defn update-thumbnail
  [id data]
  (let [lock (uuid/next)]
    (ptk/reify ::update-thumbnail
      IDeref
      (-deref [_] {:id id :data data})
      
      ptk/UpdateEvent
      (update [_ state]
        (-> state
            (assoc-in [:workspace-file :thumbnails id] data)
            (cond-> (nil? (get-in state [::update-thumbnail-lock id]))
              (assoc-in [::update-thumbnail-lock id] lock))))

      ptk/WatchEvent
      (watch [_ state stream]
        (when (= lock (get-in state [::update-thumbnail-lock id]))
          (let [stopper (->> stream (rx/filter (ptk/type? :app.main.data.workspace/finalize)))
                params {:file-id (:current-file-id state)
                        :object-id id}]
            (rx/merge
             (->> stream
                  (rx/take-until stopper)
                  (rx/filter (ptk/type? ::update-thumbnail))
                  (rx/filter #(= id (:id (deref %))))
                  (rx/debounce 2000)
                  (rx/first)
                  (rx/flat-map
                   (fn [event]
                     (let [data (:data @event)]
                       (rp/mutation! :upsert-file-object-thumbnail (assoc params :data data)))))

                  (rx/map #(fn [state] (d/dissoc-in state [::update-thumbnail-lock id]))))

             (->> (rx/of (update-thumbnail id data))
                  (rx/observe-on :async)))))))))

(defn remove-thumbnail
  [id]
  (ptk/reify ::remove-thumbnail
    ptk/UpdateEvent
    (update [_ state]
      (-> state (d/dissoc-in [:workspace-file :thumbnails id])))

    ptk/WatchEvent
    (watch [_ state _]
      (let [params {:file-id (:current-file-id state)
                    :object-id id
                    :data nil}]
        (->> (rp/mutation! :upsert-file-object-thumbnail params)
             (rx/ignore))))))

(defn- extract-frame-changes
  "Process a changes set in a commit to extract the frames that are changing"
  [[event [old-objects new-objects]]]
  (let [changes (-> event deref :changes)

        extract-ids
        (fn [{type :type :as change}]
          (case type
            :add-obj [(:id change)]
            :mod-obj [(:id change)]
            :del-obj [(:id change)]
            :reg-objects (:shapes change)
            :mov-objects (:shapes change)
            []))

        get-frame-id
        (fn [id]
          (let [shape (or (get new-objects id)
                          (get old-objects id))]

            (or (and (= :frame (:type shape)) id)
                (:frame-id shape))))

        ;; Extracts the frames and then removes nils and the root frame
        xform (comp (mapcat extract-ids)
                    (map get-frame-id)
                    (remove nil?)
                    (filter #(not= uuid/zero %))
                    (filter #(contains? new-objects %)))]

    (into #{} xform changes)))

(defn thumbnail-change?
  "Checks if a event is only updating thumbnails to ignore in the thumbnail generation process"
  [event]
  (let [changes (-> event deref :changes)

        is-thumbnail-op?
        (fn [{type :type attr :attr}]
          (and (= type :set)
               (= attr :thumbnail)))

        is-thumbnail-change?
        (fn [change]
          (and (= (:type change) :mod-obj)
               (->> change :operations (every? is-thumbnail-op?))))]

    (->> changes (every? is-thumbnail-change?))))

(defn watch-state-changes []
  (ptk/reify ::watch-state-changes
    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stopper (->> stream
                         (rx/filter #(or (= :app.main.data.workspace/finalize-page (ptk/type %))
                                         (= ::watch-state-changes (ptk/type %)))))

            objects-stream (->> (rx/concat
                                 (rx/of nil)
                                 (rx/from-atom refs/workspace-page-objects {:emit-current-value? true}))
                                ;; We need to keep the old-objects so we can check the frame for the
                                ;; deleted objects
                                (rx/buffer 2 1))

            frame-changes (->> stream
                               (rx/filter dch/commit-changes?)

                               ;; Async so we wait for additional side-effects of commit-changes
                               (rx/observe-on :async)
                               (rx/filter (comp not thumbnail-change?))
                               (rx/with-latest-from objects-stream)
                               (rx/map extract-frame-changes)
                               (rx/share))]

        (->> frame-changes
             (rx/take-until stopper)
             (rx/flat-map
              (fn [ids]
                (->> (rx/from ids)
                     (rx/map #(ptk/data-event ::force-render %))))))))))

(defn duplicate-thumbnail
  [old-id new-id]
  (ptk/reify ::duplicate-thumbnail
    ptk/UpdateEvent
    (update [_ state]
      (let [old-shape-thumbnail (get-in state [:workspace-file :thumbnails old-id])]
        (-> state (assoc-in [:workspace-file :thumbnails new-id] old-shape-thumbnail))))))



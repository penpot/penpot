;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.thumbnails
  (:require
   [app.common.data.macros :as dm]
   [app.common.pages.helpers :as cph]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as dch]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [app.util.webapi :as wapi]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defn force-render-stream
  "Stream that will inform the frame-wrapper to mount into memory"
  [id]
  (->> st/stream
       (rx/filter (ptk/type? ::force-render))
       (rx/map deref)
       (rx/filter #(= % id))
       (rx/take 1)))

(defn thumbnail-stream
  [object-id]
  (rx/create
   (fn [subs]
     (let [node (dom/query (dm/fmt "canvas.thumbnail-canvas[data-object-id='%'" object-id))]
       (if (some? node)
         (-> node
             (.toBlob (fn [blob]
                        (rx/push! subs blob)
                        (rx/end! subs))
                      "image/png"))

         ;; If we cannot find the node we send `nil` and the upsert will delete the thumbnail
         (do (rx/push! subs nil)
             (rx/end! subs)))))))

(defn update-thumbnail
  "Updates the thumbnail information for the given frame `id`"
  [page-id frame-id]
  (ptk/reify ::update-thumbnail
    ptk/WatchEvent
    (watch [_ state _]
      (let [object-id  (dm/str page-id frame-id)
            file-id (:current-file-id state)
            blob-result (thumbnail-stream object-id)]

        (->> blob-result
             (rx/merge-map
              (fn [blob]
                (if (some? blob)
                  (wapi/read-file-as-data-url blob)
                  (rx/of nil))))

             (rx/merge-map
              (fn [data]
                (let [params {:file-id file-id :object-id object-id :data data}]
                  (rx/merge
                   ;; Update the local copy of the thumbnails so we don't need to request it again
                   (rx/of #(assoc-in % [:workspace-file :thumbnails object-id] data))
                   (->> (rp/mutation! :upsert-file-object-thumbnail params)
                        (rx/ignore)))))))))))

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
            (or (and (cph/frame-shape? shape) id) (:frame-id shape))))

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

(defn watch-state-changes
  "Watch the state for changes inside frames. If a change is detected will force a rendering
  of the frame data so the thumbnail can be updated."
  []
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
                               (rx/filter (complement thumbnail-change?))
                               (rx/with-latest-from objects-stream)
                               (rx/map extract-frame-changes)
                               (rx/share))]

        (->> frame-changes
             (rx/flat-map
              (fn [ids]
                (->> (rx/from ids)
                     (rx/map #(ptk/data-event ::force-render %)))))
             (rx/take-until stopper))))))

(defn duplicate-thumbnail
  [old-id new-id]
  (ptk/reify ::duplicate-thumbnail
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (get state :current-page-id)
            old-shape-thumbnail (get-in state [:workspace-file :thumbnails (dm/str page-id old-id)])]
        (-> state (assoc-in [:workspace-file :thumbnails (dm/str page-id new-id)] old-shape-thumbnail))))))

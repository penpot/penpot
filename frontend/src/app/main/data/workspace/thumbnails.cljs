;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.thumbnails
  (:require
   [app.common.data.macros :as dm]
   [app.common.pages.helpers :as cph]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.state-helpers :as wsh]
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
     ;; We look in the DOM a canvas that 1) matches the id and 2) that it's not empty
     ;; will be empty on first rendering before drawing the thumbnail and we don't want to store that
     (let [node (dom/query (dm/fmt "canvas.thumbnail-canvas[data-object-id='%'][data-empty='false']" object-id))]
       (if (some? node)
         (-> node
             (.toBlob (fn [blob]
                        (rx/push! subs blob)
                        (rx/end! subs))
                      "image/png"))

         ;; If we cannot find the node we send `nil` and the upsert will delete the thumbnail
         (do (rx/push! subs nil)
             (rx/end! subs)))))))

(defn clear-thumbnail
  [page-id frame-id]
  (ptk/reify ::clear-thumbnail
    ptk/UpdateEvent
    (update [_ state]
      (let [object-id  (dm/str page-id frame-id)]
        (assoc-in state [:workspace-file :thumbnails object-id] nil)))))

(defn update-thumbnail
  "Updates the thumbnail information for the given frame `id`"
  ([page-id frame-id]
   (update-thumbnail nil page-id frame-id))

  ([file-id page-id frame-id]
   (ptk/reify ::update-thumbnail
     ptk/WatchEvent
     (watch [_ state _]
       (let [object-id  (dm/str page-id frame-id)
             file-id (or file-id (:current-file-id state))
             blob-result (thumbnail-stream object-id)]

         (->> blob-result
              (rx/merge-map
               (fn [blob]
                 (if (some? blob)
                   (wapi/read-file-as-data-url blob)
                   (rx/of nil))))

              (rx/merge-map
               (fn [data]
                 (if (some? file-id)
                   (let [params {:file-id file-id :object-id object-id :data data}]
                     (rx/merge
                      ;; Update the local copy of the thumbnails so we don't need to request it again
                      (rx/of #(assoc-in % [:workspace-file :thumbnails object-id] data))
                      (->> (rp/mutation! :upsert-file-object-thumbnail params)
                           (rx/ignore))))

                   (rx/empty))))))))))

(defn- extract-frame-changes
  "Process a changes set in a commit to extract the frames that are changing"
  [[event [old-data new-data]]]
  (let [changes (-> event deref :changes)

        extract-ids
        (fn [{:keys [page-id type] :as change}]
          (case type
            :add-obj [[page-id (:id change)]]
            :mod-obj [[page-id (:id change)]]
            :del-obj [[page-id (:id change)]]
            :mov-objects (->> (:shapes change) (map #(vector page-id %)))
            []))

        get-frame-id
        (fn [[page-id id]]
          (let [old-objects (wsh/lookup-data-objects old-data page-id)
                new-objects (wsh/lookup-data-objects new-data page-id)

                new-shape (get new-objects id)
                old-shape (get old-objects id)

                old-frame-id (if (cph/frame-shape? old-shape) id (:frame-id old-shape))
                new-frame-id (if (cph/frame-shape? new-shape) id (:frame-id new-shape))]

            (cond-> #{}
              (and old-frame-id (not= uuid/zero old-frame-id))
              (conj [page-id old-frame-id])

              (and new-frame-id (not= uuid/zero new-frame-id))
              (conj [page-id new-frame-id]))))]
    (into #{}
          (comp (mapcat extract-ids)
                (mapcat get-frame-id))
          changes)))

(defn watch-state-changes
  "Watch the state for changes inside frames. If a change is detected will force a rendering
  of the frame data so the thumbnail can be updated."
  []
  (ptk/reify ::watch-state-changes
    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stopper
            (->> stream
                 (rx/filter #(or (= :app.main.data.workspace/finalize-page (ptk/type %))
                                 (= ::watch-state-changes (ptk/type %)))))

            workspace-data-str
            (->> (rx/concat
                  (rx/of nil)
                  (rx/from-atom refs/workspace-data {:emit-current-value? true}))
                 ;; We need to keep the old-objects so we can check the frame for the
                 ;; deleted objects
                 (rx/buffer 2 1))

            change-str
            (->> stream
                 (rx/filter #(or (dch/commit-changes? %)
                                 (= (ptk/type %) :app.main.data.workspace.notifications/handle-file-change)))
                 (rx/observe-on :async))

            frame-changes-str
            (->> change-str
                 (rx/with-latest-from workspace-data-str)
                 (rx/flat-map extract-frame-changes)
                 (rx/share))]

        (->> (rx/merge
              (->> frame-changes-str
                   (rx/filter (fn [[page-id _]] (not= page-id (:current-page-id @st/state))))
                   (rx/map (fn [[page-id frame-id]] (clear-thumbnail page-id frame-id))))

              (->> frame-changes-str
                   (rx/filter (fn [[page-id _]] (= page-id (:current-page-id @st/state))))
                   (rx/map (fn [[_ frame-id]] (ptk/data-event ::force-render frame-id)))))
             (rx/take-until stopper))))))

(defn duplicate-thumbnail
  [old-id new-id]
  (ptk/reify ::duplicate-thumbnail
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (get state :current-page-id)
            old-shape-thumbnail (get-in state [:workspace-file :thumbnails (dm/str page-id old-id)])]
        (-> state (assoc-in [:workspace-file :thumbnails (dm/str page-id new-id)] old-shape-thumbnail))))))

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
   [app.util.timers :as ts]
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

(defn thumbnail-canvas-blob-stream
  [object-id]
  ;; Look for the thumbnail canvas to send the data to the backend
  (let [node (dom/query (dm/fmt "canvas.thumbnail-canvas[data-object-id='%'][data-ready='true']" object-id))
        stopper (->> st/stream
                     (rx/filter (ptk/type? :app.main.data.workspace/finalize-page))
                     (rx/take 1))]
    (if (some? node)
      ;; Success: we generate the blob (async call)
      (rx/create
       (fn [subs]
         (ts/raf
          #(.toBlob node (fn [blob]
                           (rx/push! subs blob)
                           (rx/end! subs))
                    "image/png"))))

      ;; Not found, we retry after delay
      (->> (rx/timer 250)
           (rx/flat-map #(thumbnail-canvas-blob-stream object-id))
           (rx/take-until stopper)))))

(defn clear-thumbnail
  [page-id frame-id]
  (ptk/reify ::clear-thumbnail
    ptk/UpdateEvent
    (update [_ state]
      (let [object-id  (dm/str page-id frame-id)]
        (update state :workspace-thumbnails dissoc object-id)))))

(defn update-thumbnail
  "Updates the thumbnail information for the given frame `id`"
  ([page-id frame-id]
   (update-thumbnail nil page-id frame-id))

  ([file-id page-id frame-id]
   (ptk/reify ::update-thumbnail
     ptk/WatchEvent
     (watch [_ state _]
       (let [object-id   (dm/str page-id frame-id)
             file-id     (or file-id (:current-file-id state))
             blob-result (thumbnail-canvas-blob-stream object-id)
             params {:file-id file-id :object-id object-id :data nil}]

         (rx/concat
          ;; Delete the thumbnail first so if we interrupt we can regenerate after
          (->> (rp/cmd! :upsert-file-object-thumbnail params)
               (rx/catch #(rx/empty)))

          ;; Remove the thumbnail temporary. If the user changes pages the thumbnail is regenerated
          (rx/of #(update % :workspace-thumbnails assoc object-id nil))

          ;; Send the update to the back-end
          (->> blob-result
               (rx/merge-map
                (fn [blob]
                  (if (some? blob)
                    (wapi/read-file-as-data-url blob)
                    (rx/of nil))))

               (rx/merge-map
                (fn [data]
                  (if (and (some? data) (some? file-id))
                    (let [params (assoc params :data data)]
                      (rx/merge
                       ;; Update the local copy of the thumbnails so we don't need to request it again
                       (rx/of #(update % :workspace-thumbnails assoc object-id data))
                       (->> (rp/cmd! :upsert-file-object-thumbnail params)
                            (rx/catch #(rx/empty))
                            (rx/ignore))))

                    (rx/empty))))
               (rx/catch #(do (.error js/console %)
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
      (let [page-id   (:current-page-id state)
            thumbnail (dm/get-in state [:workspace-thumbnails (dm/str page-id old-id)])]
        (update state :workspace-thumbnails assoc (dm/str page-id new-id) thumbnail)))))

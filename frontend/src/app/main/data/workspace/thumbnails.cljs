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
   [app.main.worker :as uw]
   [app.util.dom :as dom]
   [app.util.http :as http]
   [app.util.timers :as tm]
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

(defn get-thumbnail
  [object-id]
  ;; Look for the thumbnail canvas to send the data to the backend
  (let [node    (dom/query (dm/fmt "image.thumbnail-canvas[data-object-id='%'][data-ready='true']" object-id))
        stopper (->> st/stream
                     (rx/filter (ptk/type? :app.main.data.workspace/finalize-page))
                     (rx/take 1))]
    ;; renders #svg image
    (if (some? node)
      (->> (rx/from (wapi/create-image-bitmap-with-workaround node))
           (rx/switch-map  #(uw/ask! {:cmd :thumbnails/render-offscreen-canvas} %))
           (rx/map :result))

      ;; Not found, we retry after delay
      (->> (rx/timer 250)
           (rx/merge-map (partial get-thumbnail object-id))
           (rx/take-until stopper)))))

(defn clear-thumbnail
  [page-id frame-id]
  (ptk/reify ::clear-thumbnail
    ptk/UpdateEvent
    (update [_ state]
      (let [object-id  (dm/str page-id frame-id)]
        (when-let [uri (dm/get-in state [:workspace-thumbnails object-id])]
          (tm/schedule-on-idle (partial wapi/revoke-uri uri)))
        (update state :workspace-thumbnails dissoc object-id)))))

(defn set-workspace-thumbnail
  [object-id uri]
  (let [prev-uri* (volatile! nil)]
    (ptk/reify ::set-workspace-thumbnail
      ptk/UpdateEvent
      (update [_ state]
        (let [prev-uri (dm/get-in state [:workspace-thumbnails object-id])]
          (some->> prev-uri (vreset! prev-uri*))
          (update state :workspace-thumbnails assoc object-id uri)))

      ptk/EffectEvent
      (effect [_ _ _]
        (tm/schedule-on-idle #(some-> ^boolean @prev-uri* wapi/revoke-uri))))))

(defn duplicate-thumbnail
  [old-id new-id]
  (ptk/reify ::duplicate-thumbnail
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id   (:current-page-id state)
            thumbnail (dm/get-in state [:workspace-thumbnails (dm/str page-id old-id)])]
        (update state :workspace-thumbnails assoc (dm/str page-id new-id) thumbnail)))))

(defn update-thumbnail
  "Updates the thumbnail information for the given frame `id`"
  ([page-id frame-id]
   (update-thumbnail nil page-id frame-id))

  ([file-id page-id frame-id]
   (ptk/reify ::update-thumbnail
     ptk/WatchEvent
     (watch [_ state _]
       (let [object-id (dm/str page-id frame-id)
             file-id   (or file-id (:current-file-id state))]

         (rx/concat
          ;; Delete the thumbnail first so if we interrupt we can regenerate after
          (->> (rp/cmd! :delete-file-object-thumbnail {:file-id file-id :object-id object-id})
               (rx/catch rx/empty))

          ;; Send the update to the back-end
          (->> (get-thumbnail object-id)
               (rx/filter (fn [data] (and (some? data) (some? file-id))))
               (rx/merge-map
                (fn [uri]
                  (rx/merge
                   (rx/of (set-workspace-thumbnail object-id uri))

                   (->> (http/send! {:uri uri :response-type :blob :method :get})
                        (rx/map :body)
                        (rx/mapcat (fn [blob]
                                     ;; Send the data to backend
                                     (let [params {:file-id file-id
                                                   :object-id object-id
                                                   :media blob}]
                                       (rp/cmd! :create-file-object-thumbnail params))))
                        (rx/catch rx/empty)
                        (rx/ignore)))))

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

            workspace-data-s
            (->> (rx/concat
                  (rx/of nil)
                  (rx/from-atom refs/workspace-data {:emit-current-value? true}))
                 ;; We need to keep the old-objects so we can check the frame for the
                 ;; deleted objects
                 (rx/buffer 2 1))

            change-s
            (->> stream
                 (rx/filter #(or (dch/commit-changes? %)
                                 (= (ptk/type %) :app.main.data.workspace.notifications/handle-file-change)))
                 (rx/observe-on :async))

            frame-changes-s
            (->> change-s
                 (rx/with-latest-from workspace-data-s)
                 (rx/flat-map extract-frame-changes)
                 (rx/share))]

        (->> (rx/merge
              (->> frame-changes-s
                   (rx/filter (fn [[page-id _]] (not= page-id (:current-page-id @st/state))))
                   (rx/map (fn [[page-id frame-id]] (clear-thumbnail page-id frame-id))))

              (->> frame-changes-s
                   (rx/filter (fn [[page-id _]] (= page-id (:current-page-id @st/state))))
                   (rx/map (fn [[_ frame-id]] (ptk/data-event ::force-render frame-id)))))
             (rx/take-until stopper))))))

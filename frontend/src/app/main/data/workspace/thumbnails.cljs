;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.thumbnails
  (:require
   [app.common.data.macros :as dm]
   [app.common.logging :as log]
   [app.common.pages.helpers :as cph]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.rasterizer :as thr]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.http :as http]
   [app.util.imposters :as imps]
   [app.util.time :as tp]
   [app.util.timers :as tm]
   [app.util.webapi :as wapi]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(log/set-level! :debug)

(declare set-workspace-thumbnail)

(defn get-thumbnail
  [id]
  (let [object-id (dm/str id)
        tp (tp/tpoint-ms)]
    (->> (rx/of id)
         (rx/mapcat @imps/render-fn)
         (rx/filter #(= object-id (unchecked-get % "id")))
         (rx/take 1)
         (rx/map (fn [imposter]
                   {:data (unchecked-get imposter "data")
                    :styles (unchecked-get imposter "styles")
                    :width (unchecked-get imposter "width")}))
         (rx/mapcat thr/render)
         (rx/map (fn [blob] (wapi/create-uri blob)))
         (rx/tap #(log/debug :hint "generated thumbnail" :elapsed (dm/str (tp) "ms"))))))

(defn clear-thumbnail
  [frame-id]
  (ptk/reify ::clear-thumbnail
    ptk/UpdateEvent
    (update [_ state]
      (let [object-id (dm/str frame-id)]
            (when-let [uri (dm/get-in state [:workspace-thumbnails object-id])]
              (tm/schedule-on-idle (partial wapi/revoke-uri uri)))
            (update state :workspace-thumbnails dissoc object-id)))))

(defn set-workspace-thumbnail
  [id uri]
  (let [prev-uri* (volatile! nil)]
    (ptk/reify ::set-workspace-thumbnail
      ptk/UpdateEvent
      (update [_ state]
        (let [object-id (dm/str id)
              prev-uri (dm/get-in state [:workspace-thumbnails object-id])]
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
      (let [old-id (dm/str old-id)
            new-id (dm/str new-id)
            thumbnail (dm/get-in state [:workspace-thumbnails old-id])]
        (update state :workspace-thumbnails assoc new-id thumbnail)))))

(defn update-thumbnail
  "Updates the thumbnail information for the given frame `id`"
  ([id]
   (ptk/reify ::update-thumbnail
     ptk/WatchEvent
     (watch [_ state _]
       (let [object-id (dm/str id)
             file-id (:current-file-id state)]
         (rx/concat
          ;; Delete the thumbnail first so if we interrupt we can regenerate after
          (->> (rp/cmd! :delete-file-object-thumbnail {:file-id file-id :object-id object-id})
               (rx/catch rx/empty))

          ;; Send the update to the back-end
          (->> (get-thumbnail id)
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
                   (rx/map (fn [[_ frame-id]] (clear-thumbnail frame-id))))

              (->> frame-changes-s
                   (rx/filter (fn [[page-id _]] (= page-id (:current-page-id @st/state))))
                   (rx/map (fn [[_ frame-id]] (update-thumbnail frame-id)))))
             (rx/take-until stopper))))))

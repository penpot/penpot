;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.thumbnails
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.logging :as l]
   [app.common.thumbnails :as thc]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.notifications :as-alias wnt]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.rasterizer :as thr]
   [app.main.refs :as refs]
   [app.main.render :as render]
   #_[app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.http :as http]
   [app.util.queue :as q]
   [app.util.time :as tp]
   [app.util.timers :as tm]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(l/set-level! :info)

(declare update-thumbnail)

(defn resolve-request
  "Resolves the request to generate a thumbnail for the given ids."
  [item]
  (let [file-id (unchecked-get item "file-id")
        page-id  (unchecked-get item "page-id")
        shape-id (unchecked-get item "shape-id")
        tag (unchecked-get item "tag")
        requester (unchecked-get item "requester")]
    (st/emit! (update-thumbnail file-id page-id shape-id tag requester))))

;; Defines the thumbnail queue
(defonce queue
  (q/create resolve-request (/ 1000 30)))

(defn create-request
  "Creates a request to generate a thumbnail for the given ids."
  [file-id page-id shape-id tag requester]
  #js {:file-id file-id :page-id page-id :shape-id shape-id :tag tag :requester requester})

(defn find-request
  "Returns true if the given item matches the given ids."
  [file-id page-id shape-id tag item]
  (and (= file-id (unchecked-get item "file-id"))
       (= page-id (unchecked-get item "page-id"))
       (= shape-id (unchecked-get item "shape-id"))
       (= tag (unchecked-get item "tag"))))

(defn request-thumbnail
  "Enqueues a request to generate a thumbnail for the given ids."
  ([file-id page-id shape-id tag]
   (request-thumbnail file-id page-id shape-id tag "unknown"))
  ([file-id page-id shape-id tag requester]
   (ptk/reify ::request-thumbnail
     ptk/EffectEvent
     (effect [_ _ _]
       (l/dbg :hint "request thumbnail" :requester requester :file-id file-id :page-id page-id :shape-id shape-id :tag tag)
       (q/enqueue-unique
        queue
        (create-request file-id page-id shape-id tag requester)
        (partial find-request file-id page-id shape-id tag))))))

;; This function first renders the HTML calling `render/render-frame` that
;; returns HTML as a string, then we send that data to the iframe rasterizer
;; that returns the image as a Blob. Finally we create a URI for that blob.
(defn get-thumbnail
  "Returns the thumbnail for the given ids"
  [state file-id page-id frame-id tag & {:keys [object-id]}]

  (let [object-id (or object-id (thc/fmt-object-id file-id page-id frame-id tag))
        tp        (tp/tpoint-ms)
        objects   (wsh/lookup-objects state file-id page-id)
        shape     (get objects frame-id)]

    (->> (render/render-frame objects shape object-id)
         (rx/take 1)
         (rx/filter some?)
         (rx/mapcat thr/render)
         (rx/map (fn [blob] (wapi/create-uri blob)))
         (rx/tap #(l/dbg :hint "thumbnail rendered"
                         :elapsed (dm/str (tp) "ms"))))))

(defn clear-thumbnail
  ([file-id page-id frame-id tag]
   (clear-thumbnail (thc/fmt-object-id file-id page-id frame-id tag)))
  ([object-id]
   (let [emit-rpc? (volatile! false)]
     (ptk/reify ::clear-thumbnail
       cljs.core/IDeref
       (-deref [_] object-id)

       ptk/UpdateEvent
       (update [_ state]
         (let [uri (dm/get-in state [:workspace-thumbnails object-id])]
           (if (some? uri)
             (do
               (l/dbg :hint "clear thumbnail" :object-id object-id)
               (vreset! emit-rpc? true)
               (tm/schedule-on-idle (partial wapi/revoke-uri uri))
               (update state :workspace-thumbnails dissoc object-id))

             state)))))))

(defn- assoc-thumbnail
  [object-id uri]
  (let [prev-uri* (volatile! nil)]
    (ptk/reify ::assoc-thumbnail
      ptk/UpdateEvent
      (update [_ state]
        (let [prev-uri (dm/get-in state [:workspace-thumbnails object-id])]
          (some->> prev-uri (vreset! prev-uri*))
          (l/trc :hint "assoc thumbnail" :object-id object-id :uri uri)
          (update state :workspace-thumbnails assoc object-id uri)))

      ptk/EffectEvent
      (effect [_ _ _]
        (tm/schedule-on-idle
         (fn []
           (when-let [uri (deref prev-uri*)]
             (wapi/revoke-uri uri))))))))

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
  "Updates the thumbnail information for the given `id`"

  [file-id page-id frame-id tag requester]
  (let [object-id (thc/fmt-object-id file-id page-id frame-id tag)]
    (ptk/reify ::update-thumbnail
      cljs.core/IDeref
      (-deref [_] object-id)

      ptk/WatchEvent
      (watch [_ state stream]
        (l/dbg :hint "update thumbnail" :object-id object-id :tag tag :requester requester)
        ;; Send the update to the back-end
        (->> (get-thumbnail state file-id page-id frame-id tag)
             (rx/mapcat (fn [uri]
                          (rx/merge
                           (rx/of (assoc-thumbnail object-id uri))
                           (->> (http/send! {:uri uri :response-type :blob :method :get})
                                (rx/map :body)
                                #_(rx/mapcat (fn [blob]
                                             ;; Send the data to backend
                                             (let [params {:file-id file-id
                                                           :object-id object-id
                                                           :media blob
                                                           :tag (or tag "frame")
                                                           :requester requester}]
                                               (rp/cmd! :create-file-object-thumbnail params))))
                                (rx/catch rx/empty)
                                (rx/ignore)))))
             (rx/catch (fn [cause]
                         (.error js/console cause)
                         (rx/empty)))

             ;; We cancel all the stream if user starts editing while
             ;; thumbnail is generating
             (rx/take-until
              (->> stream
                   (rx/filter (ptk/type? ::clear-thumbnail))
                   (rx/filter #(= (deref %) object-id)))))))))

(defn- extract-root-frame-changes
  "Process a changes set in a commit to extract the frames that are changing"
  [page-id [event [old-data new-data]]]
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
        (fn [[_ id]]
          (let [old-objects  (wsh/lookup-data-objects old-data page-id)
                new-objects  (wsh/lookup-data-objects new-data page-id)

                new-shape    (get new-objects id)
                old-shape    (get old-objects id)

                old-frame-id (if (cfh/frame-shape? old-shape) id (:frame-id old-shape))
                new-frame-id (if (cfh/frame-shape? new-shape) id (:frame-id new-shape))]

            (cond-> #{}
              (cfh/root-frame? old-objects old-frame-id)
              (conj old-frame-id)

              (cfh/root-frame? new-objects new-frame-id)
              (conj new-frame-id))))]

    (into #{}
          (comp (mapcat extract-ids)
                (filter (fn [[page-id']] (= page-id page-id')))
                (mapcat get-frame-id))
          changes)))

(defn watch-state-changes
  "Watch the state for changes inside frames. If a change is detected will force a rendering
  of the frame data so the thumbnail can be updated."
  [file-id page-id]
  (ptk/reify ::watch-state-changes
    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stopper-s (rx/filter
                       (fn [event]
                         (as-> (ptk/type event) type
                           (or (= :app.main.data.workspace/finalize-page type)
                               (= ::watch-state-changes type))))
                       stream)

            workspace-data-s
            (->> (rx/concat
                  (rx/of nil)
                  (rx/from-atom refs/workspace-data {:emit-current-value? true}))
                 ;; We need to keep the old-objects so we can check the frame for the
                 ;; deleted objects
                 (rx/buffer 2 1)
                 (rx/share))

            local-changes-s
            (->> stream
                 (rx/filter dch/commit-changes?)
                 (rx/with-latest-from workspace-data-s)
                 (rx/merge-map (partial extract-root-frame-changes page-id))
                 (rx/tap #(l/trc :hint "incoming change" :origin "local" :frame-id (dm/str %))))

            notification-changes-s
            (->> stream
                 (rx/filter (ptk/type? ::wnt/handle-file-change))
                 (rx/observe-on :async)
                 (rx/with-latest-from workspace-data-s)
                 (rx/merge-map (partial extract-root-frame-changes page-id))
                 (rx/tap #(l/trc :hint "incoming change" :origin "notifications" :frame-id (dm/str %))))

            persistence-changes-s
            (->> stream
                 (rx/filter (ptk/type? ::update))
                 (rx/map deref)
                 (rx/filter (fn [[file-id page-id]]
                              (and (= file-id file-id)
                                   (= page-id page-id))))
                 (rx/map (fn [[_ _ frame-id]] frame-id))
                 (rx/tap #(l/trc :hint "incoming change" :origin "persistence" :frame-id (dm/str %))))

            all-changes-s
            (->> (rx/merge
                  ;; LOCAL CHANGES
                  local-changes-s
                  ;; NOTIFICATIONS CHANGES
                  notification-changes-s
                  ;; PERSISTENCE CHANGES
                  persistence-changes-s)

                 (rx/share))

            ;; BUFFER NOTIFIER (window of 5s of inactivity)
            notifier-s
            (->> all-changes-s
                 (rx/debounce 1000)
                 (rx/tap #(l/trc :hint "buffer initialized")))]

        (->> (rx/merge
              ;; Perform instant thumbnail cleaning of affected frames
              ;; and interrupt any ongoing update-thumbnail process
              ;; related to current frame-id
              (->> all-changes-s
                   (rx/map #(clear-thumbnail file-id page-id % "frame")))

              ;; Generate thumbnails in batchs, once user becomes
              ;; inactive for some instant
              (->> all-changes-s
                   (rx/buffer-until notifier-s)
                   (rx/mapcat #(into #{} %))
                   (rx/map #(request-thumbnail file-id page-id % "frame" "watch-state-changes"))))

             (rx/take-until stopper-s))))))

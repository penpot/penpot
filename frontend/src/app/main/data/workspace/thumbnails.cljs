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
   [app.common.time :as ct]
   [app.common.types.component :as ctc]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.persistence :as-alias dps]
   [app.main.data.workspace.notifications :as-alias wnt]
   [app.main.data.workspace.pages :as-alias dwpg]
   [app.main.rasterizer :as thr]
   [app.main.refs :as refs]
   [app.main.render :as render]
   [app.main.repo :as rp]
   [app.util.queue :as q]
   [app.util.timers :as tm]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

(l/set-level! :warn)

(defn- find-request
  [params item]
  (and (= (unchecked-get params "file-id")
          (unchecked-get item "file-id"))
       (= (unchecked-get params "page-id")
          (unchecked-get item "page-id"))
       (= (unchecked-get params "shape-id")
          (unchecked-get item "shape-id"))
       (= (unchecked-get params "tag")
          (unchecked-get item "tag"))))

(defn- create-request
  "Creates a request to generate a thumbnail for the given ids."
  [file-id page-id shape-id tag]
  #js {:file-id file-id
       :page-id page-id
       :shape-id shape-id
       :tag tag})

;; Defines the thumbnail queue
(defonce queue
  (q/create find-request (/ 1000 30)))

(defn clear-queue!
  []
  (l/dbg :hint "clearing thumbnail queue")
  (q/clear! queue))

;; This function first renders the HTML calling `render/render-frame` that
;; returns HTML as a string, then we send that data to the iframe rasterizer
;; that returns the image as a Blob. Finally we create a URI for that blob.
(defn- render-thumbnail
  "Returns the thumbnail for the given ids"
  [state file-id page-id frame-id tag]
  (let [object-id (thc/fmt-object-id file-id page-id frame-id tag)
        tp        (ct/tpoint-ms)
        objects   (-> (dsh/lookup-file-data state file-id)
                      (dsh/get-page page-id)
                      :objects)
        shape     (get objects frame-id)]

    (->> (render/render-frame objects shape object-id)
         (rx/take 1)
         (rx/filter some?)
         (rx/mapcat thr/render)
         (rx/tap #(l/dbg :hint "thumbnail rendered"
                         :elapsed (dm/str (tp) "ms"))))))

(defn- request-thumbnail
  "Enqueues a request to generate a thumbnail for the given ids."
  [state file-id page-id shape-id tag]
  (let [request (create-request file-id page-id shape-id tag)]
    (q/enqueue-unique queue request (partial render-thumbnail state file-id page-id shape-id tag))))

(defn clear-thumbnail
  ([file-id page-id frame-id tag]
   (clear-thumbnail file-id (thc/fmt-object-id file-id page-id frame-id tag)))
  ([file-id object-id]
   (let [pending (volatile! false)]
     (ptk/reify ::clear-thumbnail
       cljs.core/IDeref
       (-deref [_] object-id)

       ptk/UpdateEvent
       (update [_ state]
         (update state :thumbnails
                 (fn [thumbs]
                   (if-let [uri (get thumbs object-id)]
                     (do (vreset! pending uri)
                         (dissoc thumbs object-id))
                     thumbs))))

       ptk/WatchEvent
       (watch [_ _ _]
         (if-let [uri @pending]
           (do
             (l/trc :hint "clear-thumbnail" :uri uri)
             (when (str/starts-with? uri "blob:")
               (tm/schedule-on-idle (partial wapi/revoke-uri uri)))

             (let [params {:file-id file-id
                           :object-id object-id}]
               (->> (rp/cmd! :delete-file-object-thumbnail params)
                    (rx/catch rx/empty)
                    (rx/ignore))))
           (rx/empty)))))))

(defn- assoc-thumbnail
  [object-id uri]
  (let [prev-uri* (volatile! nil)]
    (ptk/reify ::assoc-thumbnail
      ptk/UpdateEvent
      (update [_ state]
        (let [prev-uri (dm/get-in state [:thumbnails object-id])]
          (some->> prev-uri (vreset! prev-uri*))
          (l/trc :hint "assoc thumbnail" :object-id object-id :uri uri)
          (update state :thumbnails assoc object-id uri)))

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
            thumbnail (dm/get-in state [:thumbnails old-id])]
        (update state :thumbnails assoc new-id thumbnail)))))

(defn update-thumbnail
  "Updates the thumbnail information for the given `id`"
  [file-id page-id frame-id tag requester]
  (let [object-id (thc/fmt-object-id file-id page-id frame-id tag)]
    (ptk/reify ::update-thumbnail
      cljs.core/IDeref
      (-deref [_] object-id)

      ptk/WatchEvent
      (watch [_ state stream]
        (l/dbg :hint "update thumbnail" :requester requester :object-id object-id :tag tag)
        (let [tp (ct/tpoint-ms)]
          ;; Send the update to the back-end
          (->> (request-thumbnail state file-id page-id frame-id tag)
               (rx/mapcat (fn [blob]
                            (let [uri    (wapi/create-uri blob)
                                  params {:file-id file-id
                                          :object-id object-id
                                          :media blob
                                          :tag (or tag "frame")}]

                              (rx/merge
                               (rx/of (assoc-thumbnail object-id uri))
                               (->> (rp/cmd! :create-file-object-thumbnail params)
                                    (rx/catch rx/empty)
                                    (rx/ignore))))))

               (rx/catch (fn [cause]
                           (.error js/console cause)
                           (rx/empty)))

               (rx/tap #(l/dbg :hint "thumbnail updated" :elapsed (dm/str (tp) "ms")))

               ;; We cancel all the stream if user starts editing while
               ;; thumbnail is generating
               (rx/take-until
                (->> stream
                     (rx/filter (ptk/type? ::clear-thumbnail))
                     (rx/filter #(= (deref %) object-id))))))))))

(defn- extract-frame-changes
  "Process a changes set in a commit to extract the frames that are changing"
  [page-id [event [old-data new-data]]]

  (let [changes (:changes event)
        lookup-data-objects
        (fn [data page-id]
          (dm/get-in data [:pages-index page-id :objects]))


        extract-ids
        (fn [{:keys [page-id type] :as change}]
          (case type
            :add-obj [[page-id (:id change)]]
            :mod-obj [[page-id (:id change)]]
            :del-obj [[page-id (:id change)]]
            :mov-objects (->> (:shapes change) (map #(vector page-id %)))
            []))

        get-frame-ids
        (fn get-frame-ids [id]
          (let [old-objects     (lookup-data-objects old-data page-id)
                new-objects     (lookup-data-objects new-data page-id)

                new-shape       (get new-objects id)
                old-shape       (get old-objects id)

                old-frame-id    (if (cfh/frame-shape? old-shape) id (:frame-id old-shape))
                new-frame-id    (if (cfh/frame-shape? new-shape) id (:frame-id new-shape))

                root-frame-old? (cfh/root-frame? old-objects old-frame-id)
                root-frame-new? (cfh/root-frame? new-objects new-frame-id)
                instance-root?  (ctc/instance-root? new-shape)]

            (cond-> #{}
              root-frame-old?
              (conj ["frame" old-frame-id])

              root-frame-new?
              (conj ["frame" new-frame-id])

              instance-root?
              (conj ["component" id])

              (and (uuid? (:frame-id old-shape))
                   (not= uuid/zero (:frame-id old-shape)))
              (into (get-frame-ids (:frame-id old-shape)))

              (and (uuid? (:frame-id new-shape))
                   (not= uuid/zero (:frame-id new-shape)))
              (into (get-frame-ids (:frame-id new-shape))))))]

    (into #{}
          (comp (mapcat extract-ids)
                (filter (fn [[page-id']] (= page-id page-id')))
                (map (fn [[_ id]] id))
                (mapcat get-frame-ids))
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
                           (or (= ::dwpg/finalize-page type)
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

            ;; All commits stream, indepentendly of the source of the commit
            all-commits-s
            (->> stream
                 (rx/filter dch/commit?)
                 (rx/map deref)
                 (rx/observe-on :async)
                 (rx/with-latest-from workspace-data-s)
                 (rx/merge-map (partial extract-frame-changes page-id))
                 (rx/tap #(l/trc :hint "inconming change" :origin "all" :frame-id (dm/str %)))
                 (rx/share))

            notifier-s
            (->> stream
                 (rx/filter (ptk/type? ::dps/commit-persisted))
                 (rx/debounce 5000)
                 (rx/tap #(l/trc :hint "buffer initialized")))]

        (->> (rx/merge
              ;; Perform instant thumbnail cleaning of affected frames
              ;; and interrupt any ongoing update-thumbnail process
              ;; related to current frame-id
              (->> all-commits-s
                   (rx/mapcat (fn [[tag frame-id]]
                                (rx/of (clear-thumbnail file-id page-id frame-id tag)))))

              ;; Generate thumbnails in batches, once user becomes
              ;; inactive for some instant.
              (->> all-commits-s
                   (rx/buffer-until notifier-s)
                   (rx/mapcat #(into #{} %))
                   (rx/map (fn [[tag frame-id]]
                             (update-thumbnail file-id page-id frame-id tag "watch-state-changes")))))

             (rx/take-until stopper-s))))))

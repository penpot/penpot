;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.exports.assets
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.modal :as modal]
   [app.main.data.persistence :as dwp]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [app.util.websocket :as ws]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def default-timeout 5000)

(defn toggle-detail-visibililty
  []
  (ptk/reify ::toggle-detail-visibililty
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:export :detail-visible] not))))

(defn toggle-widget-visibililty
  []
  (ptk/reify ::toggle-widget-visibility
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:export :widget-visible] not))))

(defn clear-export-state
  [id]
  (ptk/reify ::clear-export-state
    ptk/UpdateEvent
    (update [_ state]
      ;; only clear if the existing export is the same
      (let [existing-id (-> state :export :id)]
        (if (and (some? existing-id)
                 (not= id existing-id))
          state
          (dissoc state :export))))))


(defn show-workspace-export-dialog
  [{:keys [selected origin]}]
  (ptk/reify ::show-workspace-export-dialog
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id  (:current-file-id state)
            page-id  (:current-page-id state)
            selected (or selected (dsh/lookup-selected state page-id {}))

            shapes   (if (seq selected)
                       (dsh/lookup-shapes state selected)
                       (reverse (dsh/filter-shapes state #(pos? (count (:exports %))))))

            exports  (for [shape  shapes
                           export (:exports shape)]
                       (-> export
                           (assoc :enabled true)
                           (assoc :page-id page-id)
                           (assoc :file-id file-id)
                           (assoc :object-id (:id shape))
                           (assoc :shape (dissoc shape :exports))
                           (assoc :name (:name shape))))]

        (rx/of (modal/show :export-shapes
                           {:exports (vec exports) :origin origin}))))))

(defn show-viewer-export-dialog
  [{:keys [shapes page-id file-id share-id exports]}]
  (ptk/reify ::show-viewer-export-dialog
    ptk/WatchEvent
    (watch [_ _ _]
      (let [exports (for [shape shapes
                          export exports]
                      (-> export
                          (assoc :enabled true)
                          (assoc :page-id page-id)
                          (assoc :file-id file-id)
                          (assoc :object-id (:id shape))
                          (assoc :shape (dissoc shape :exports))
                          (assoc :name (:name shape))
                          (cond-> share-id (assoc :share-id share-id))))]
        (rx/of (modal/show :export-shapes {:exports (vec exports) :origin "viewer"})))))) #_TODO

(defn show-workspace-export-frames-dialog
  [frames]
  (ptk/reify ::show-workspace-export-frames-dialog
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id  (:current-file-id state)
            page-id  (:current-page-id state)
            exports  (mapv (fn [frame]
                             {:enabled true
                              :page-id page-id
                              :file-id file-id
                              :object-id (:id frame)
                              :shape frame
                              :name (:name frame)})
                           frames)]

        (rx/of (modal/show :export-frames
                           {:exports exports
                            :origin "workspace:menu"}))))))

(defn- initialize-export-status
  [exports cmd resource]
  (ptk/reify ::initialize-export-status
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :export {:in-progress true
                            :resource-id (:id resource)
                            :healthy? true
                            :error false
                            :progress 0
                            :widget-visible true
                            :detail-visible true
                            :exports exports
                            :last-update (ct/now)
                            :cmd cmd}))))

(defn- update-export-status
  [{:keys [done status resource-uri filename mtype] :as data}]
  (ptk/reify ::update-export-status
    ptk/UpdateEvent
    (update [_ state]
      (let [time-diff (ct/diff-ms (get-in state [:export :last-update]) (ct/now))
            healthy?  (< time-diff 6000)]
        (cond-> state
          (= status "running")
          (update :export assoc :progress done :last-update (ct/now) :healthy? healthy?)

          (= status "error")
          (update :export assoc :in-progress false :error (:cause data) :last-update (ct/now) :healthy? healthy?)

          (= status "ended")
          (update :export assoc :in-progress false :last-update (ct/now) :healthy? healthy?))))

    ptk/WatchEvent
    (watch [_ _ _]
      (when (= status "ended")
        (dom/trigger-download-uri filename mtype resource-uri)))))

(defn request-simple-export
  [{:keys [export]}]
  (ptk/reify ::request-simple-export
    ptk/UpdateEvent
    (update [_ state]
      (update state :export assoc :in-progress true :id uuid/zero))

    ptk/WatchEvent
    (watch [_ state _]
      (let [profile-id (:profile-id state)
            params     {:exports [export]
                        :profile-id profile-id
                        :cmd :export-shapes
                        :wait true}]
        (rx/concat
         (rx/of ::dwp/force-persist)

         ;; Wait the persist to be succesfull
         (->> (rx/from-atom refs/persistence-state {:emit-current-value? true})
              (rx/filter #(or (nil? %) (= :saved %)))
              (rx/first)
              (rx/timeout 400 (rx/empty)))

         (->> (rp/cmd! :export params)
              (rx/map (fn [{:keys [filename mtype uri]}]
                        (dom/trigger-download-uri filename mtype uri)
                        (clear-export-state uuid/zero)))
              (rx/catch (fn [cause]
                          (rx/concat
                           (rx/of (clear-export-state uuid/zero))
                           (rx/throw cause))))))))))

(defn request-multiple-export
  [{:keys [exports cmd]
    :or {cmd :export-shapes}
    :as params}]
  (ptk/reify ::request-multiple-export
    ptk/WatchEvent
    (watch [_ state _]
      (let [resource-id (volatile! nil)
            profile-id  (:profile-id state)
            ws-conn     (:ws-conn state)
            params      {:exports exports
                         :cmd cmd
                         :profile-id profile-id
                         :wait false}

            progress-stream
            (->> (ws/get-rcv-stream ws-conn)
                 (rx/filter ws/message-event?)
                 (rx/map :payload)
                 (rx/filter #(= :export-update (:type %)))
                 (rx/filter #(= @resource-id (:resource-id %)))
                 (rx/share))

            stopper
            (rx/filter #(or (= "ended" (:status %))
                            (= "error" (:status %)))
                       progress-stream)]

        (swap! st/ongoing-tasks conj :export)

        (rx/merge
         ;; Force that all data is persisted; best effort.
         (rx/of ::dwp/force-persist)

         ;; Launch the exportation process and stores the resource id
         ;; locally.
         (->> (rp/cmd! :export params)
              (rx/map (fn [{:keys [id] :as resource}]
                        (vreset! resource-id id)
                        (initialize-export-status exports cmd resource))))

         ;; We proceed to update the export state with incoming
         ;; progress updates. We delay the stopper for give some time
         ;; to update the status with ended or errored status before
         ;; close the stream.
         (->> progress-stream
              (rx/map update-export-status)
              (rx/take-until (rx/delay 500 stopper))
              (rx/finalize (fn []
                             (swap! st/ongoing-tasks disj :export))))

         ;; We hide need to hide the ui elements of the export after
         ;; some interval. We also delay a little bit more the stopper
         ;; for ensure that after some security time, the stream is
         ;; completely closed.
         (->> progress-stream
              (rx/filter #(= "ended" (:status %)))
              (rx/take 1)
              (rx/delay default-timeout)
              (rx/map #(clear-export-state @resource-id))
              (rx/take-until (rx/delay 6000 stopper))))))))

(defn request-export
  [{:keys [exports] :as params}]
  (if (= 1 (count exports))
    (request-simple-export (assoc params :export (first exports)))
    (request-multiple-export params)))

(defn retry-last-export
  []
  (ptk/reify ::retry-last-export
    ptk/WatchEvent
    (watch [_ state _]
      (let [params (select-keys (:export state) [:exports :cmd])]
        (when (seq params)
          (rx/of (request-multiple-export params)))))))

(defn export-shapes-event
  [exports origin]
  (let [types (reduce (fn [counts {:keys [type]}]
                        (if (#{:png :jpeg :webp :svg :pdf} type)
                          (update counts type inc)
                          counts))
                      {:png 0, :jpeg 0, :webp 0, :pdf 0, :svg 0}
                      exports)]
    (ptk/event
     ::ev/event (merge types
                       {::ev/name "export-shapes"
                        ::ev/origin origin
                        :num-shapes (count exports)}))))

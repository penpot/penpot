;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.exports
  (:require
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.persistence :as dwp]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [app.util.time :as dt]
   [app.util.websocket :as ws]
   [beicon.core :as rx]
   [potok.core :as ptk]))

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
  ([] (show-workspace-export-dialog nil))
  ([{:keys [selected]}]
   (ptk/reify ::show-workspace-export-dialog
     ptk/WatchEvent
     (watch [_ state _]
       (let [file-id  (:current-file-id state)
             page-id  (:current-page-id state)

             filename (-> (wsh/lookup-page state page-id) :name)
             selected (or selected (wsh/lookup-selected state page-id {}))

             shapes   (if (seq selected)
                        (wsh/lookup-shapes state selected)
                        (reverse (wsh/filter-shapes state #(pos? (count (:exports %))))))

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
                            {:exports (vec exports)
                             :filename filename})))))))

(defn show-viewer-export-dialog
  [{:keys [shapes filename page-id file-id exports]}]
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
                          (assoc :name (:name shape))))]
        (rx/of (modal/show :export-shapes {:exports (vec exports)
                                           :filename filename}))))))

(defn show-workspace-export-frames-dialog
  ([frames]
   (ptk/reify ::show-workspace-export-frames-dialog
     ptk/WatchEvent
     (watch [_ state _]
       (let [file-id  (:current-file-id state)
             page-id  (:current-page-id state)
             filename (-> (wsh/lookup-page state page-id)
                          :name
                          (dm/str ".pdf"))

             exports  (for [frame  frames]
                        {:enabled true
                         :page-id page-id
                         :file-id file-id
                         :frame-id (:id frame)
                         :shape frame
                         :name (:name frame)})]

         (rx/of (modal/show :export-frames
                            {:exports (vec exports)
                             :filename filename})))))))

(defn- initialize-export-status
  [exports filename resource-id query-name]
  (ptk/reify ::initialize-export-status
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :export {:in-progress true
                            :resource-id resource-id
                            :healthy? true
                            :error false
                            :progress 0
                            :widget-visible true
                            :detail-visible true
                            :exports exports
                            :filename filename
                            :last-update (dt/now)
                            :query-name query-name}))))

(defn- update-export-status
  [{:keys [progress status resource-id name] :as data}]
  (ptk/reify ::update-export-status
    ptk/UpdateEvent
    (update [_ state]
      (let [time-diff (dt/diff (dt/now)
                               (get-in state [:export :last-update]))
            healthy? (< time-diff (dt/duration {:seconds 6}))]
        (cond-> state
          (= status "running")
          (update :export assoc :progress (:done progress) :last-update (dt/now) :healthy? healthy?)

          (= status "error")
          (update :export assoc :error (:cause data) :last-update (dt/now) :healthy? healthy?)

          (= status "ended")
          (update :export assoc :in-progress false :last-update (dt/now) :healthy? healthy?))))

    ptk/WatchEvent
    (watch [_ _ _]
      (when (= status "ended")
        (->> (rp/query! :download-export-resource resource-id)
             (rx/delay 500)
             (rx/map #(dom/trigger-download name %)))))))

(defn request-simple-export
  [{:keys [export filename]}]
  (ptk/reify ::request-simple-export
    ptk/UpdateEvent
    (update [_ state]
      (update state :export assoc :in-progress true :id uuid/zero))

    ptk/WatchEvent
    (watch [_ state _]
      (let [profile-id (:profile-id state)
            params     {:exports [export]
                        :profile-id profile-id}]
        (rx/concat
         (rx/of ::dwp/force-persist)
         (->> (rp/query! :export-shapes-simple params)
              (rx/map (fn [data]
                        (dom/trigger-download filename data)
                        (clear-export-state uuid/zero)))
              (rx/catch (fn [cause]
                          (prn "KKKK" cause)
                          (rx/concat
                           (rx/of (clear-export-state uuid/zero))
                           (rx/throw cause))))))))))

(defn request-multiple-export
  [{:keys [filename exports query-name]
    :or {query-name :export-shapes-multiple}
    :as params}]
  (ptk/reify ::request-multiple-export
    ptk/WatchEvent
    (watch [_ state _]
      (let [resource-id (volatile! nil)
            profile-id  (:profile-id state)
            ws-conn     (:ws-conn state)
            params      {:exports exports
                         :name filename
                         :profile-id profile-id
                         :wait false}

            progress-stream
            (->> (ws/get-rcv-stream ws-conn)
                 (rx/filter ws/message-event?)
                 (rx/map :payload)
                 (rx/filter #(= :export-update (:type %)))
                 (rx/filter #(= @resource-id (:resource-id %)))
                 (rx/share))

            stoper
            (rx/filter #(or (= "ended" (:status %))
                            (= "error" (:status %)))
                       progress-stream)]

        (swap! st/ongoing-tasks conj :export)

        (rx/merge
         ;; Force that all data is persisted; best effort.
         (rx/of ::dwp/force-persist)

         ;; Launch the exportation process and stores the resource id
         ;; locally.
         (->> (rp/query! query-name params)
              (rx/tap (fn [{:keys [id]}]
                        (vreset! resource-id id)))
              (rx/map (fn [{:keys [id]}]
                        (initialize-export-status exports filename id query-name))))

         ;; We proceed to update the export state with incoming
         ;; progress updates. We delay the stoper for give some time
         ;; to update the status with ended or errored status before
         ;; close the stream.
         (->> progress-stream
              (rx/map update-export-status)
              (rx/take-until (rx/delay 500 stoper))
              (rx/finalize (fn []
                             (swap! st/ongoing-tasks disj :export))))

         ;; We hide need to hide the ui elements of the export after
         ;; some interval. We also delay a litle bit more the stopper
         ;; for ensure that after some security time, the stream is
         ;; completelly closed.
         (->> progress-stream
              (rx/filter #(= "ended" (:status %)))
              (rx/take 1)
              (rx/delay default-timeout)
              (rx/map #(clear-export-state @resource-id))
              (rx/take-until (rx/delay 6000 stoper))))))))


(defn retry-last-export
  []
  (ptk/reify ::retry-last-export
    ptk/WatchEvent
    (watch [_ state _]
      (let [params (select-keys (:export state) [:filename :exports :query-name])]
        (when (seq params)
          (rx/of (request-multiple-export params)))))))


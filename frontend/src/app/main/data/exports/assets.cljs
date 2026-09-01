;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.exports.assets
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.event :as ev]
   [app.main.data.exports.wasm :as wasm.exports]
   [app.main.data.helpers :as dsh]
   [app.main.data.modal :as modal]
   [app.main.data.persistence :as dwp]
   [app.main.features :as features]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [app.util.websocket :as ws]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

(def default-timeout 5000)

(defn normalize-export
  [{:keys [object-id name] :as export}]
  (assoc export :name (if (str/blank? name)
                        (str object-id)
                        name)))

(defn- normalize-exports
  [exports]
  (mapv normalize-export exports))

(defn- normalize-export-shapes-params
  [{:keys [exports] :as params}]
  (cond-> params
    (seq exports)
    (assoc :exports (normalize-exports exports))))

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

            page      (dsh/lookup-page state)
            page-name (:name page)

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
                            :origin origin
                            :name page-name}))))))

(defn show-viewer-export-dialog
  [{:keys [shapes page-id file-id share-id exports name]}]
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
        (rx/of (modal/show :export-shapes {:exports (vec exports)
                                           :origin "viewer"
                                           :name name})))))) #_TODO

(defn show-workspace-export-frames-dialog
  [frames]
  (ptk/reify ::show-workspace-export-frames-dialog
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id   (:current-file-id state)
            page-id   (:current-page-id state)
            page      (dsh/lookup-page state)
            page-name (:name page)
            exports   (mapv (fn [frame]
                              {:enabled true
                               :page-id page-id
                               :file-id file-id
                               :object-id (:id frame)
                               :shape frame
                               :name (:name frame)})
                            frames)]

        (rx/of (modal/show :export-frames
                           {:exports exports
                            :origin "workspace:menu"
                            :name page-name}))))))

(defn- initialize-export-status
  "`job` is only present on the job API path; without it the widget counts the
  exports the client submitted, exactly as it always has."
  [exports cmd resource {:keys [job-id total status backend] :as job}]
  (ptk/reify ::initialize-export-status
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :export (cond-> {:in-progress true
                                    :resource-id (:id resource)
                                    :healthy? true
                                    :error false
                                    :progress 0
                                    :widget-visible true
                                    :detail-visible true
                                    :exports exports
                                    :last-update (ct/now)
                                    :cmd cmd}
                             (some? job)
                             (assoc :job-id job-id
                                    :total total
                                    :status status
                                    :backend backend))))))

(defn- update-export-status
  [{:keys [done total status resource-uri filename mtype] :as data}]
  (ptk/reify ::update-export-status
    ptk/UpdateEvent
    (update [_ state]
      (let [time-diff (ct/diff-ms (get-in state [:export :last-update]) (ct/now))
            healthy?  (< time-diff 6000)
            ;; The legacy path has no server-side figures to track; it keeps
            ;; reporting progress over the client's own list.
            job?      (some? (get-in state [:export :job-id]))]
        (cond-> state
          job?
          (update :export assoc :status status)

          (and job? (some? total))
          (update :export assoc :total total)

          (= status "running")
          (update :export assoc :progress done :last-update (ct/now) :healthy? healthy?)

          (= status "error")
          (update :export assoc :in-progress false :error (:cause data) :last-update (ct/now) :healthy? healthy?)

          (= status "cancelling")
          (update :export assoc :last-update (ct/now) :healthy? healthy?)

          (= status "cancelled")
          (update :export assoc :in-progress false :last-update (ct/now) :healthy? healthy?)

          (= status "ended")
          (update :export assoc :in-progress false :last-update (ct/now) :healthy? healthy?))))

    ptk/WatchEvent
    (watch [_ _ _]
      (when (= status "ended")
        (dom/trigger-download-uri filename mtype resource-uri)))))

;; The exporter is at capacity. Not a crash: the widget says so and the user
;; retries, instead of the generic error dialog.
(def ^:private saturation-codes #{:queue-full})

(defn- export-failed
  "Reports a failure that happened before the export ever started, so the widget
  settles instead of waiting for progress that will never arrive."
  [exports cmd cause]
  (ptk/reify ::export-failed
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :export {:in-progress false
                            :widget-visible true
                            :detail-visible true
                            :healthy? true
                            :progress 0
                            :total (count exports)
                            :exports exports
                            :cmd cmd
                            :error (or (ex-message cause) true)
                            :error-code (:code (ex-data cause))
                            :last-update (ct/now)}))))

(defn cancel-export
  "Stops the running export. Only reachable on the job API path, where the
  exporter can actually abort the work.

  The widget settles from here rather than from the job's `cancelled` message:
  the outcome is known once the request returns, and waiting on a round trip
  through redis and the websocket would leave it stuck whenever that message is
  missed."
  []
  (ptk/reify ::cancel-export
    ptk/WatchEvent
    (watch [_ state _]
      (when-let [job-id (get-in state [:export :job-id])]
        (let [resource-id (get-in state [:export :resource-id])
              settle      (rx/concat
                           (rx/of (update-export-status {:status "cancelled"}))
                           (->> (rx/of (clear-export-state resource-id))
                                (rx/delay default-timeout)))]
          (rx/concat
           ;; Stopping is not instantaneous: the request has to reach the
           ;; exporter and the work has to unwind.
           (rx/of (update-export-status {:status "cancelling"}))
           (->> (rp/cmd! :cancel-export-job {:job-id job-id})
                (rx/mapcat (fn [_] settle))
                ;; Already finished, or the exporter is gone; either way
                ;; there is nothing left to stop.
                (rx/catch (fn [_] settle)))))))))

;; TODO: Remove once we support WASM SVG export
(def ^:private wasm-export-types #{:jpeg :webp :png :pdf :svg})

(defn- wasm-export-enabled?
  [state]
  (and (contains? cf/flags :wasm-export)
       (features/active-feature? state "render-wasm/v1")))

(defn- use-wasm-export?
  "Whether to take the client-side WASM export path for `export`."
  [state export]
  (and (wasm-export-enabled? state)
       (contains? wasm-export-types (:type export))))

(defn- request-simple-export-wasm
  [export]
  (ptk/reify ::request-simple-export-wasm
    ptk/EffectEvent
    (effect [_ _ _]
      (case (:type export)
        :pdf (wasm.exports/export-pdf export)
        :svg (wasm.exports/export-svg export)
        (wasm.exports/export-image export)))))

(defn request-simple-export
  [{:keys [export]}]
  (let [export (normalize-export export)]
    (ptk/reify ::request-simple-export
      ptk/UpdateEvent
      (update [_ state]
        (cond-> state
          (not (use-wasm-export? state export))
          (update :export assoc :in-progress true :id uuid/zero)))

      ptk/WatchEvent
      (watch [_ state _]
        (if (use-wasm-export? state export)
          (rx/of (request-simple-export-wasm export))
          (let [profile-id (:profile-id state)
                params     (normalize-export-shapes-params {:exports [export]
                                                            :profile-id profile-id
                                                            :cmd :export-shapes
                                                            :wait true
                                                            :is-wasm (wasm-export-enabled? state)})]
            (rx/concat
             (dwp/force-persist-and-wait 400)

             (->> (rp/cmd! :export params)
                  (rx/map (fn [{:keys [filename mtype uri]}]
                            (dom/trigger-download-uri filename mtype uri)
                            (clear-export-state uuid/zero)))
                  (rx/catch (fn [cause]
                              (rx/concat
                               (rx/of (clear-export-state uuid/zero))
                               (rx/throw cause))))))))))))

(defn request-multiple-export
  [{:keys [exports cmd name]
    :or {cmd :export-shapes}
    :as params}]
  (let [exports (normalize-exports exports)]
    (ptk/reify ::request-multiple-export
      ptk/WatchEvent
      (watch [_ state _]
        (let [resource-id (volatile! nil)
              profile-id  (:profile-id state)
              ws-conn     (:ws-conn state)
              params      (cond->
                           {:exports exports
                            :cmd cmd
                            :profile-id profile-id
                            :force-multiple true
                            :is-wasm (wasm-export-enabled? state)}
                            (some? name)
                            (assoc :name name))

              progress-stream
              (->> (ws/get-rcv-stream ws-conn)
                   (rx/filter ws/message-event?)
                   (rx/map :payload)
                   (rx/filter #(= :export-update (:type %)))
                   (rx/filter #(= @resource-id (:resource-id %)))
                   (rx/share))

              stopper
              (rx/filter #(or (= "ended" (:status %))
                              (= "error" (:status %))
                              (= "cancelled" (:status %)))
                         progress-stream)]

          (swap! st/ongoing-tasks conj :export)

          (rx/merge
           ;; Force that all data is persisted; best effort.
           (rx/of ::dwp/force-persist)

           ;; Launch the exportation process and stores the resource id
           ;; locally. With wasm export active the job API is used instead: it
           ;; answers with the exporter's own object count and gives a handle
           ;; to cancel.
           (->> (if (wasm-export-enabled? state)
                  (->> (rp/cmd! :create-export-job params)
                       (rx/map (fn [{job-id :id :keys [total] :as job}]
                                 (vreset! resource-id (:resource-id job))
                                 (initialize-export-status exports cmd
                                                           {:id (:resource-id job)}
                                                           {:job-id job-id
                                                            :total total
                                                            :status (:state job)
                                                            :backend (:backend job)}))))
                  (->> (rp/cmd! :export params)
                       (rx/map (fn [{:keys [id] :as resource}]
                                 (vreset! resource-id id)
                                 (initialize-export-status exports cmd resource nil)))))
                (rx/catch (fn [cause]
                            ;; Saturation is an answer, not a fault.
                            (if (contains? saturation-codes (:code (ex-data cause)))
                              (rx/of (export-failed exports cmd cause))
                              (rx/concat
                               (rx/of (export-failed exports cmd cause))
                               (rx/throw cause))))))

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
                (rx/filter #(or (= "ended" (:status %))
                                (= "cancelled" (:status %))))
                (rx/take 1)
                (rx/delay default-timeout)
                (rx/map #(clear-export-state @resource-id))
                (rx/take-until (rx/delay 6000 stopper)))))))))

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
          (rx/of (request-export params)))))))

(defn export-shapes-event
  [exports origin]
  (let [types (reduce (fn [counts {:keys [type]}]
                        (if (#{:png :jpeg :webp :svg :pdf} type)
                          (update counts type inc)
                          counts))
                      {:png 0, :jpeg 0, :webp 0, :pdf 0, :svg 0}
                      exports)]
    (ev/event (merge types
                     {::ev/name "export-shapes"
                      ::ev/origin origin
                      :num-shapes (count exports)}))))

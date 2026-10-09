;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.exports.assets
  "The shapes and frames exportation, over the jobs substrate.

  Every export here is one `:export-assets` job: the backend creates it
  (`create-export-assets-job`) and freezes the items, the external
  exporter worker renders them, and the client follows its life on the
  websocket with `dj/watch-job`, the same channel the file exports of
  `data.exports.files` ride since they became jobs. The milestones of
  the job (`:preparing`, `:rendering`, `:packaging`, with the `objects`
  and `pages` counters) become the figures the progress widget shows;
  the completed row carries the artifact under its `result`, for the
  download; and the X of the widget cancels the job by its id.

  The client-side wasm render of a single object is the exception: it
  never leaves the browser, so it keeps rendering and downloading
  locally."
  (:require
   [app.common.time :as ct]
   [app.main.data.event :as ev]
   [app.main.data.exports.wasm :as wasm.exports]
   [app.main.data.helpers :as dsh]
   [app.main.data.jobs :as dj]
   [app.main.data.modal :as modal]
   [app.main.data.persistence :as dwp]
   [app.main.features :as features]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.dom :as dom]
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
                                           :name name}))))))

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
                               :name (:name frame)
                               ;; pages render as one pdf: the kind
                               ;; travels declared, the items fully typed
                               :type :pdf
                               :scale 1
                               :suffix ""})
                            frames)]

        (rx/of (modal/show :export-frames
                           {:exports exports
                            :origin "workspace:menu"
                            :name page-name}))))))

;; ---- THE PROGRESS WIDGET STATE ------------------------------------
;; The events this namespace emits for the export progress widget. The
;; job's channel (`dj/watch-job`) turns into them on its way up.

(defn- initialize-export-status
  "The state a just created job starts at: the widget shows it queued,
  with no figures yet — the counter comes with the first milestone the
  worker publishes, and counts itself down from the items meanwhile.
  The creation answers the row status (`pending`): the widget names it
  queued, because nobody works on the job yet."
  [exports cmd {:keys [id status]}]
  (ptk/reify ::initialize-export-status
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :export {:in-progress true
                            :healthy? true
                            :error false
                            :progress 0
                            :widget-visible true
                            :detail-visible true
                            :exports exports
                            :last-update (ct/now)
                            :cmd cmd
                            :job-id id
                            :status (if (= "pending" status) "queued" status)}))))

(defn- counter-of
  "The figures of one milestone: `objects` counts the shapes and
  `pages` counts the frames, both as `current` exported and `total`
  planned. A job publishes one kind or the other; this takes whatever
  it carries."
  [counters]
  (or (:objects counters) (:pages counters)))

(defn- add-milestone
  "One beat of the job into the widget: what has landed and what is
  coming, named by the stage the run is in. The status moves to running
  on the first beat, figures or not. A milestone without figures (the
  first breath of the worker) names the stage but keeps the figures the
  widget already holds. A milestone that arrives once the widget
  settled from a cancel or an error is ignored."
  [payload]
  (let [figures (counter-of (:counters payload))]
    (ptk/reify ::add-milestone
      ptk/UpdateEvent
      (update [_ state]
        (if-not (get-in state [:export :in-progress])
          state
          (-> (update state :export assoc
                      :status "running"
                      :stage (:stage payload)
                      :last-update (ct/now))
              (cond-> (some? figures)
                (update :export assoc
                        :progress (:current figures)
                        :total (or (:total figures)
                                   (get-in state [:export :total]))))))))))

(defn- add-outcome
  "The last row of the job as the end of the widget. A completed job
  downloads its artifact right away and the widget goes away after a
  moment; a failed one stays, naming the error; a cancelled one just
  settles; and a cancelling step keeps the widget alive while the
  cancel flies."
  [{:keys [status result error]}]
  (let [downloadable? (or (= "completed" status) (= "ended" status))]
    (ptk/reify ::add-outcome
      ptk/UpdateEvent
      (update [_ state]
        (update state :export (fn [export]
                                (if-not (and (map? export)
                                             (or (:in-progress export)
                                                 (= "cancelling" (:status export))))
                                  export
                                  (cond
                                    (= "cancelling" status)
                                    (assoc export :status "cancelling"
                                           :last-update (ct/now))

                                    downloadable?
                                    (assoc export :status "ended"
                                           :in-progress false
                                           :last-update (ct/now))

                                    (= "failed" status)
                                    (assoc export :status "error"
                                           :in-progress false
                                           :error (:hint error)
                                           :error-code (:code error)
                                           :last-update (ct/now))

                                    (= "cancelled" status)
                                    (assoc export :status "cancelled"
                                           :in-progress false
                                           :last-update (ct/now))

                                    :else export)))))

      ptk/WatchEvent
      (watch [_ _ _]
        (when downloadable?
          (dom/trigger-download-uri (:filename result)
                                    (:mtype result)
                                    (:resource-uri result)))
        (when (or downloadable? (= "cancelled" status))
          ;; the widget has its moment naming what happened, and goes
          ;; away
          (->> (rx/of (clear-export-state nil))
               (rx/delay default-timeout)))))))

;; The exporter is at capacity. Not a crash: the widget says so and the user
;; retries, instead of the generic error dialog.
(def ^:private saturation-codes #{:queue-full :max-quote-reached})

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
  "Stops the running export: the X of the widget asks the backend to
  cancel its job — fire and forget, the job may have just ended on its
  own — and settles the widget itself, because the outcome is known
  once the cancel is dispatched; waiting on the row and the websocket
  would leave it stuck whenever that message is missed. The widget
  keeps its cancelled state on display a moment, and goes away."
  []
  (ptk/reify ::cancel-export
    ptk/WatchEvent
    (watch [_ state _]
      (when-let [job-id (get-in state [:export :job-id])]
        (dj/cancel-job job-id)
        ;; Stopping is not instantaneous: the row is marked, and the
        ;; worker stops at its next beat.
        (rx/of (add-outcome {:status "cancelling"}))))))

;; ---- THE JOB FLOW -------------------------------------------------

(defn- ->widget-event
  "One emission of the job taken from `dj/watch-job` is the event the
  widget consumes: the payload of a progress event carries the
  milestone, and the row at the end answers with the outcome."
  [emission]
  (cond
    (and (= :progress (:kind emission))
         (map? (:payload emission)))
    (add-milestone (:payload emission))

    (contains? emission :status)
    (add-outcome emission)))

(defn- export-stream!
  "The whole life of one export job as a stream of widget events: the
  creation of the job, then its channel (`dj/watch-job`) mapped into
  the vocabulary of the widget, and the stream over once the outcome
  lands. The start and retry events of the channel are dropped by the
  mapper.

  A creation that could not even happen (the export queue saturated,
  mostly) reports on the widget and, unless it is saturation, rethrows
  for the global error handling of the store."
  [ws-conn exports cmd {:keys [force-multiple name is-wasm kind]}]
  (let [params (cond-> {:exports exports
                        :is-wasm is-wasm}
                 (some? name)
                 (assoc :name name)

                 (some? kind)
                 (assoc :kind kind)

                 (some? force-multiple)
                 (assoc :force-multiple force-multiple))]

    (->> (rp/cmd! :create-export-assets-job {:params params})
         (rx/mapcat
          (fn [job]
            (rx/concat
             (rx/of (initialize-export-status exports cmd job))
             (->> (dj/watch-job ws-conn (:id job))
                  (rx/map ->widget-event)
                  (rx/filter some?)))))
         (rx/catch (fn [cause]
                     (let [failed (export-failed exports cmd cause)]
                       (if (contains? saturation-codes (:code (ex-data cause)))
                         (rx/of failed)
                         (rx/concat (rx/of failed)
                                    (rx/throw cause))))))
         (rx/finalize (fn []
                        (swap! st/ongoing-tasks disj :export))))))

(def ^:private wasm-export-types #{:jpeg :webp :png :pdf :svg})

(defn- wasm-export-enabled?
  "True when the active renderer is render-wasm."
  [state]
  (features/active-feature? state "render-wasm/v1"))

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
      ptk/WatchEvent
      (watch [_ state _]
        (if (use-wasm-export? state export)
          (rx/of (request-simple-export-wasm export))
          (let [ws-conn (:ws-conn state)]
            (swap! st/ongoing-tasks conj :export)
            (rx/concat
             (dwp/force-persist-and-wait 400)
             (export-stream! ws-conn [export] nil
                             {:is-wasm (wasm-export-enabled? state)}))))))))

(defn request-multiple-export
  [{:keys [exports cmd name]
    :or {cmd :export-shapes}
    :as params}]
  (let [exports (normalize-exports exports)]
    (ptk/reify ::request-multiple-export
      ptk/WatchEvent
      (watch [_ state _]
        (let [ws-conn (:ws-conn state)]
          (swap! st/ongoing-tasks conj :export)
          (rx/merge
           ;; Force that all data is persisted; best effort.
           (rx/of ::dwp/force-persist)

           (->> (export-stream! ws-conn exports cmd
                                {:force-multiple true
                                 :name name
                                 :kind (if (= :export-frames cmd) :frames :shapes)
                                 :is-wasm (wasm-export-enabled? state)}))))))))

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

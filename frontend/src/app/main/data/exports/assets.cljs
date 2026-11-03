;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.exports.assets
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.persistence :as dwp]
   [app.main.errors :as errors]
   [app.main.features :as features]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.worker :as mw]
   [app.render-wasm.api :as wasm.api]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.webapi :as wapi]
   [app.util.websocket :as ws]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

(def default-timeout 5000)

;; Longest wait for the pending changes of the exported files to be saved.
(def save-wait-timeout-ms 10000)

(defn- export-file-ids
  [exports]
  (into #{} (keep :file-id) exports))

(defn- warn-unsaved-changes
  "Warns that the export may miss the changes of `file-ids` not saved yet."
  [file-ids]
  (ptk/reify ::warn-unsaved-changes
    ptk/WatchEvent
    (watch [_ state _]
      (when (dwp/unsaved-changes? state file-ids)
        (rx/of (ntf/show {:content (tr "workspace.export.unsaved-changes")
                          :type :toast
                          :level :warning
                          :timeout 8000}))))))

(defn- export-failed
  "Shows an export failure as a toast, keeping the workspace open: the
  failure is never raised to the global error handler, which would
  replace the workspace with an error page."
  [cause]
  (ptk/reify ::export-failed
    ptk/EffectEvent
    (effect [_ _ _]
      (errors/flash :cause cause
                    :type (if (#{:server-error :internal} (:type (ex-data cause)))
                            :unhandled
                            :handled)
                    :hint (tr "errors.export-failed")))))

(defn- export-widget-failed
  "Reports a failure that happened before the export ever started: the
  widget settles instead of waiting for progress that will never arrive,
  and the toast says what happened. Nothing is raised to the global
  error handler, which would replace the workspace with an error page."
  [exports cmd cause]
  (ptk/reify ::export-widget-failed
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
                            :last-update (ct/now)}))

    ptk/EffectEvent
    (effect [_ _ _]
      (errors/flash :cause cause
                    :type (if (#{:server-error :internal} (:type (ex-data cause)))
                            :unhandled
                            :handled)
                    :hint (tr "errors.export-failed")))))

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
            ;; Local WASM jobs also store a client job-id so the widget can
            ;; show cancelling/cancelled (it keys those labels on job-id).
            job?      (some? (get-in state [:export :job-id]))
            current   (get-in state [:export :status])
            ;; Cancel settles the widget immediately; a late worker
            ;; `:progress` must not flip status back to "running".
            terminal? (contains? #{"cancelled" "cancelling" "ended" "error"} current)
            apply?    (or (not= status "running") (not terminal?))]
        (cond-> state
          (and job? apply?)
          (update :export assoc :status status)

          (and job? apply? (some? total))
          (update :export assoc :total total)

          (and apply? (= status "running"))
          (update :export assoc :progress done :last-update (ct/now) :healthy? healthy?)

          (= status "error")
          (update :export assoc :in-progress false :error (:cause data) :last-update (ct/now) :healthy? healthy?)

          (= status "cancelling")
          (update :export assoc :last-update (ct/now) :healthy? healthy?)

          (= status "cancelled")
          (update :export assoc :in-progress false :last-update (ct/now) :healthy? healthy?)

          (= status "ended")
          (update :export assoc :in-progress false :progress done :last-update (ct/now) :healthy? healthy?))))

    ptk/WatchEvent
    (watch [_ _ _]
      (when (= status "ended")
        (dom/trigger-download-uri filename mtype resource-uri)))))

(def ^:private local-export-job-id*
  "Job id of the in-flight worker multi-export, if any."
  (atom nil))

(defn cancel-export
  "Stops the running export. On the job API path the exporter aborts; on the
  local WASM path the worker is told to stop and the widget settles here.

  Local cancel settles on the client: if the worker is stuck before the first
  cancel check, waiting for a `:cancelled` reply would leave the UI hung."
  []
  (ptk/reify ::cancel-export
    ptk/WatchEvent
    (watch [_ state _]
      (let [backend     (get-in state [:export :backend])
            job-id      (get-in state [:export :job-id])
            resource-id (get-in state [:export :resource-id])
            settle      (rx/concat
                         (rx/of (update-export-status {:status "cancelled"}))
                         (->> (rx/of (clear-export-state resource-id))
                              (rx/delay default-timeout)))]
        (cond
          (= "local" backend)
          (do
            (when-let [local-job-id @local-export-job-id*]
              (mw/emit! {:cmd :exports/cancel :job-id local-job-id}))
            ;; Mark cancelled so a late `:done` from the worker is ignored.
            (reset! local-export-job-id* nil)
            settle)

          (some? job-id)
          (rx/concat
           ;; Stopping is not instantaneous: the request has to reach the
           ;; exporter and the work has to unwind.
           (rx/of (update-export-status {:status "cancelling"}))
           (->> (rp/cmd! :cancel-export-job {:job-id job-id})
                (rx/mapcat (fn [_] settle))
                ;; Already finished, or the exporter is gone; either way
                ;; there is nothing left to stop.
                (rx/catch (fn [_] settle)))))))))

(def ^:private wasm-export-types #{:jpeg :webp :png :pdf :svg})

(defn- wasm-export-enabled?
  "True when the active renderer is render-wasm."
  [state]
  (features/active-feature? state "render-wasm/v1"))

(defn- export-target
  "Where a single-shape export runs.

  With render-wasm off → `:exporter` (server).
  With render-wasm on → never the server API: always `:client` when the
  format is supported, except true `:too-large` (beyond raster limits —
  the exporter would fail too). Oversized-for-GPU rasters still run
  locally via the CPU path in `export-bytes`."
  [state {:keys [type object-id scale]}]
  (cond
    (not (and (wasm-export-enabled? state)
              (contains? wasm-export-types type)))
    :exporter

    (contains? #{:pdf :svg} type)
    :client

    ;; Raster: only refuse when WASM reports true `:too-large`. The legacy
    ;; `:exporter` code means “use CPU raster” — we do that locally.
    :else
    (if (= :too-large (wasm.api/export-target object-id (or scale 1) type))
      :too-large
      :client)))

(declare request-local-export-wasm)

(defn request-simple-export
  [{:keys [export]}]
  (let [export (normalize-export export)
        target (volatile! nil)]
    (ptk/reify ::request-simple-export
      ptk/UpdateEvent
      (update [_ state]
        (vreset! target (export-target state export))
        (cond-> state
          (= :exporter @target)
          (update :export assoc :in-progress true :id uuid/zero)))

      ptk/WatchEvent
      (watch [_ state _]
        (case @target
          :client
          (rx/of (request-local-export-wasm {:exports [export]
                                             :cmd :export-shapes
                                             :name (:name export)
                                             :kind :single}))

          :too-large
          (rx/of (ntf/error (tr "errors.export-too-large")))

          (let [profile-id (:profile-id state)
                params     (normalize-export-shapes-params {:exports [export]
                                                            :profile-id profile-id
                                                            :cmd :export-shapes
                                                            :wait true
                                                            :is-wasm (wasm-export-enabled? state)})]
            (rx/concat
             (dwp/force-persist-files-and-wait (export-file-ids [export]) save-wait-timeout-ms)
             (rx/of (warn-unsaved-changes (export-file-ids [export])))

             (->> (rp/cmd! :export params)
                  (rx/map (fn [{:keys [filename mtype uri]}]
                            (dom/trigger-download-uri filename mtype uri)
                            (clear-export-state uuid/zero)))
                  (rx/catch (fn [cause]
                              (rx/of (clear-export-state uuid/zero)
                                     (export-failed cause))))))))))))

(defn- export-file-page-ids
  [state exports]
  (let [file-id (or (:file-id (first exports)) (:current-file-id state))
        page-id (or (:page-id (first exports)) (:current-page-id state))]
    [file-id page-id]))

(defn collect-export-objects
  "Live page objects needed for `exports`: each root plus its descendants.
  Falls back to the full page when the closure is empty (missing ids)."
  [state exports]
  (let [[file-id page-id] (export-file-page-ids state exports)
        objects  (or (dsh/lookup-page-objects state file-id page-id) {})
        root-ids (into #{} (map :object-id) exports)
        ids      (into #{}
                       (mapcat #(cfh/get-children-ids-with-self objects %))
                       root-ids)
        selected (select-keys objects ids)]
    (if (seq selected)
      selected
      objects)))

(defn- export-page-background
  [state exports]
  (let [[file-id page-id] (export-file-page-ids state exports)
        page (dsh/lookup-page state file-id page-id)]
    (:background page)))

(defn- worker-export-entries
  "Payload for the worker: drop `:shape` and other UI-only keys."
  [exports]
  (mapv (fn [{:keys [object-id type scale name suffix]}]
          {:object-id object-id
           :type type
           :scale (or scale 1)
           :name name
           :suffix suffix})
        exports))

(defn- request-local-export-wasm
  "Client-side export on the shared worker WASM module: one file (`:single`),
  a ZIP of shapes (`:zip`), or a multi-page boards PDF (`:pdf-frames`)."
  [{:keys [exports cmd name kind]
    :or {cmd :export-shapes}}]
  (ptk/reify ::request-local-export-wasm
    ptk/WatchEvent
    (watch [_ state _]
      (let [resource-id (uuid/next)
            job-id      (uuid/next)
            total       (count exports)
            kind        (or kind
                            (if (= cmd :export-frames) :pdf-frames :zip))
            objects     (collect-export-objects state exports)
            background  (export-page-background state exports)
            entries     (worker-export-entries exports)
            clear-later (->> (rx/of (clear-export-state resource-id))
                             (rx/delay default-timeout))]
        (reset! local-export-job-id* job-id)
        (swap! st/ongoing-tasks conj :export)

        (rx/concat
         (rx/of (initialize-export-status exports cmd
                                          {:id resource-id}
                                          {:job-id job-id
                                           :total total
                                           :status "running"
                                           :backend "local"}))
         (->> (mw/ask-many!
               {:cmd :exports/run-local
                :job-id job-id
                :kind kind
                :exports entries
                :objects objects
                :background background
                :name name})
              (rx/mapcat
               (fn [{:keys [type] :as message}]
                 ;; Cancel clears `local-export-job-id*` and settles the
                 ;; widget locally. Ignore every later worker message so a
                 ;; stray `:progress` cannot flip the UI back to "running".
                 (if (nil? @local-export-job-id*)
                   (rx/empty)
                   (case type
                     :progress
                     (rx/of (update-export-status {:status "running"
                                                   :done (:done message)
                                                   :total (:total message)}))

                     :done
                     (let [uri (wapi/create-uri (:blob message))]
                       (rx/concat
                        (rx/of (update-export-status
                                {:status "ended"
                                 :done total
                                 :filename (:filename message)
                                 :mtype (:mtype message)
                                 :resource-uri uri}))
                        clear-later))

                     :cancelled
                     (rx/concat
                      (rx/of (update-export-status {:status "cancelled"}))
                      clear-later)

                     :error
                     (rx/of (export-widget-failed
                             exports cmd
                             (ex-info (or (:hint message) "worker export failed")
                                      (or (:data message)
                                          {:type :internal
                                           :code :worker-export-error}))))

                     (rx/empty)))))
              (rx/catch
               (fn [cause]
                 (if (= :export-cancelled (:type (ex-data cause)))
                   (rx/concat
                    (rx/of (update-export-status {:status "cancelled"}))
                    clear-later)
                   (rx/of (export-widget-failed exports cmd cause)))))
              (rx/finalize
               (fn []
                 (reset! local-export-job-id* nil)
                 (swap! st/ongoing-tasks disj :export)))))))))

(defn- request-multiple-export-server
  [{:keys [exports cmd name]
    :or {cmd :export-shapes}}]
  (ptk/reify ::request-multiple-export-server
    ptk/WatchEvent
    (watch [_ state _]
      (let [resource-id (volatile! nil)
            ;; Emits when the export request fails.
            failed-s    (rx/subject)
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
                 (rx/take-until failed-s)
                 (rx/share))

            stopper
            (rx/filter #(or (= "ended" (:status %))
                            (= "error" (:status %))
                            (= "cancelled" (:status %)))
                       progress-stream)

            file-ids
            (export-file-ids exports)]

        (swap! st/ongoing-tasks conj :export)

        (rx/concat
         (dwp/force-persist-files-and-wait file-ids save-wait-timeout-ms)
         (rx/of (warn-unsaved-changes file-ids))
         (rx/merge
          ;; Updates the export state with each progress message, until
          ;; shortly after the export ends or fails.
          (->> progress-stream
               (rx/map update-export-status)
               (rx/take-until (rx/delay 500 stopper))
               (rx/finalize (fn []
                              (swap! st/ongoing-tasks disj :export))))

          ;; Clears the export state a while after the export ends or is
          ;; cancelled.
          (->> progress-stream
               (rx/filter #(or (= "ended" (:status %))
                               (= "cancelled" (:status %))))
               (rx/take 1)
               (rx/delay default-timeout)
               (rx/map #(clear-export-state @resource-id))
               (rx/take-until (rx/delay 6000 stopper)))

          ;; Launches the exportation process and stores the resource id
          ;; locally. Goes last so a failure reaches the progress listeners
          ;; above: the push to `failed-s` releases the leave-page warning
          ;; even though no progress message will ever arrive, and nothing
          ;; is raised to the global error handler, which would replace the
          ;; workspace with an error page.
          (->> (rp/cmd! :export params)
               (rx/map (fn [{:keys [id] :as resource}]
                         (vreset! resource-id id)
                         (initialize-export-status exports cmd resource nil)))
               (rx/catch (fn [cause]
                           (rx/push! failed-s cause)
                           (rx/of (export-widget-failed exports cmd cause)))))))))))

(defn request-multiple-export
  [{:keys [exports cmd]
    :or {cmd :export-shapes}
    :as params}]
  (let [exports (normalize-exports exports)
        params  (assoc params :exports exports :cmd cmd)]
    (ptk/reify ::request-multiple-export
      ptk/WatchEvent
      (watch [_ state _]
        (if (wasm-export-enabled? state)
          (rx/of (request-local-export-wasm params))
          (rx/of (request-multiple-export-server params)))))))

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

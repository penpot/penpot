;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.run
  "Serial renderer benchmark runs over a retained build.

  One run owns one run home, one loopback host and at most one Chromium process.
  Fresh cases prepare once per slot on a new page/context. Reuse cases prepare
  once and run every slot on that page/context. A failed attempt consumes its
  slot. Browser failures leave the current case's remaining slots unattempted
  and continue with the next case in a new browser. The run record is atomically
  replaced after every preparation, attempt and diagnostic change."
  (:require
   ["module" :as node-module]
   ["os" :as os]
   ["path" :as path]
   [app.common.transit :as transit]
   [benches.render-wasm.declarations :as declarations]
   [benches.render-wasm.failures :as failures]
   [benches.render-wasm.measurement :as measurement]
   [benches.render-wasm.node.artifacts :as artifacts]
   [benches.render-wasm.node.browser :as browser]
   [benches.render-wasm.node.build :as build]
   [benches.render-wasm.node.catalog :as catalog]
   [benches.render-wasm.node.log :as log]
   [benches.render-wasm.node.process :as process]
   [benches.render-wasm.node.runs :as runs]
   [benches.render-wasm.node.server :as server]
   [benches.render-wasm.report.format :as format]
   [benches.render-wasm.report.summarize :as summary]
   [benches.render-wasm.result :as result]))

(defn- now
  []
  (.toISOString (js/Date.)))

(defn- message-of
  [cause]
  (or (ex-message cause) (str cause)))

(defn- cause-evidence
  "Plain failure evidence for a rejected browser, server or persistence action."
  [cause fallback-phase]
  (let [data  (ex-data cause)
        phase (or (:phase data) (name fallback-phase))]
    (cond-> {:status "failed"
             :phase (if (keyword? phase) (name phase) (str phase))
             :message (message-of cause)
             :cause (failures/describe-cause cause)}
      (:partial data) (assoc :partial (:partial data)))))

(defn- failure-evidence
  "Projects a bridge failure response into diagnostics evidence."
  [evidence fallback-phase]
  (if (map? evidence)
    (cond-> (select-keys evidence [:status :phase :message :cause :partial])
      (nil? (:status evidence)) (assoc :status "failed")
      (nil? (:phase evidence)) (assoc :phase (name fallback-phase)))
    {:status "failed"
     :phase (name fallback-phase)
     :message (str evidence)}))

(defn- interruption
  [signal]
  (when (and signal (.-aborted ^js signal))
    (let [reason (.-reason ^js signal)
          data   (ex-data reason)]
      {:signal (if (string? reason) reason (or (:signal data) "SIGINT"))
       :cause reason})))

(defn- interrupted?
  [state]
  (some? (interruption (:signal state))))

(defn- write-log
  [state output text]
  (try
    (output text)
    (catch :default cause
      (reset! (:storage-error-ref state) cause)
      (throw cause))))

(defn- warn-safely
  "Appends runner diagnostics without replacing an error already under handling."
  [state text]
  (try
    (write-log state log/warn text)
    (catch :default _
      nil)))

(defn- persist
  [state]
  (try
    (runs/write-record! (:home state) @(:run-ref state))
    (catch :default cause
      (reset! (:storage-error-ref state) cause)
      (throw cause))))

(defn- record-preparation
  [state preparation]
  (swap! (:run-ref state) result/record-preparation preparation)
  (persist state)
  (write-log state log/debug (str "Prepared " (result/case-name (:case-id preparation)))))

(defn- record-attempt
  [state attempt]
  (swap! (:run-ref state) result/record-attempt attempt)
  (persist state)
  (let [remaining (get-in @(:run-ref state) [:remaining (:case-id attempt)])
        total     (+ (:warmups (:opts state)) (:repetitions (:opts state)))
        index     (- total (:warmups remaining) (:repetitions remaining))]
    (write-log state (if (= :failed (:outcome attempt)) log/warn log/info)
               (str "  attempt " index "/" total " " (name (:outcome attempt))
                    (when (:warmup? attempt) " (warmup)")))))

(defn- record-failure
  [state case-id stage evidence]
  (let [failure (cond-> (assoc (select-keys evidence [:status :phase :message :cause :partial])
                               :stage stage)
                  (some? case-id) (assoc :case-id case-id))]
    (reset! (:harness-failed? state) true)
    (swap! (:run-ref state) update-in [:metadata :diagnostics :failures]
           (fnil conj []) failure)
    (persist state)
    (write-log state log/warn (str (name stage) " failed"
                                   (when (some? case-id) (str " for " (result/case-name case-id)))
                                   ": " (:message failure) "\n"))
    failure))

(defn- record-screenshot-status
  [state case-id status]
  (swap! (:run-ref state) assoc-in
         [:metadata :diagnostics :screenshots (result/case-name case-id)] status)
  (persist state))

(defn- screenshot-status
  [state case-id]
  (get-in @(:run-ref state)
          [:metadata :diagnostics :screenshots (result/case-name case-id)]))

(defn- record-screenshot-skip
  [state case-id reason]
  (when (:screenshots? (:opts state))
    (when-not (screenshot-status state case-id)
      (record-screenshot-status state case-id {:status :skipped :reason reason}))))

(defn- ensure-screenshot-status
  [state case reason]
  (when (:screenshots? (:opts state))
    (when-not (screenshot-status state (:id case))
      (record-screenshot-skip state (:id case) reason))))

(defn- ^:async screenshot-case
  [state case page]
  (when (:screenshots? (:opts state))
    (let [{:keys [file path]} (runs/screenshot-path (:home state) (:id case))
          opts   (:opts state)
          bounds {:deadline (process/deadline (:startup-timeout-ms opts))
                  :signal (:signal state) :phase :screenshot}]
      (try
        (await ((get-in state [:services :screenshot]) page path bounds))
        (record-screenshot-status state (:id case) {:file file :status :ok})
        (write-log state log/debug (str "Screenshot " file))
        (catch :default cause
          (record-screenshot-status state (:id case)
                                    {:file file :status :failed
                                     :message (message-of cause)
                                     :cause (failures/describe-cause cause)})
          (write-log state log/warn (str "Screenshot failed for "
                                         (result/case-name (:id case)) ": "
                                         (message-of cause) "\n")))))))

(defn- case-slots
  "Returns each scheduled slot in order: all warmups, then measured slots.
  True marks a warmup."
  [opts]
  (vec (concat (repeat (:warmups opts) true)
               (repeat (:repetitions opts) false))))

(defn- renderer-url
  [build-id file]
  (str "/renderer/" file "?build=" (js/encodeURIComponent build-id)))

(defn- ^:async prepare-case
  [state case page deadline]
  (let [opts    (:opts state)
        build-id (:id (:selected-build state))
        request (transit/encode-str
                 {:case case
                  :preparation-id (str (random-uuid))
                  :timeout-ms (:timeout-ms opts)
                  :module-url (renderer-url build-id "render-wasm.js")
                  :wasm-url (renderer-url build-id "render-wasm.wasm")})]
    (await ((get-in state [:services :prepare])
            page request
            {:deadline deadline :signal (:signal state) :phase :preparation}))))

(defn- ^:async run-attempt
  [state page preparation-id deadline]
  (let [request (transit/encode-str
                 {:preparation-id preparation-id
                  :timeout-ms (max 1 (process/remaining deadline))})]
    (await ((get-in state [:services :run])
            page request
            {:deadline deadline :signal (:signal state) :phase :attempt}))))

(defn- ^:async recycle-browser
  [state]
  (when-let [owner @(:owner-ref state)]
    (reset! (:owner-ref state) nil)
    (try
      (await ((get-in state [:services :close]) owner))
      (catch :default cause
        (write-log state log/warn (str "Browser cleanup failed: " (message-of cause)))))))

(defn- ^:async close-current-page
  [state]
  (let [owner      @(:owner-ref state)
        connected? (get-in state [:services :connected?])]
    (if (and owner connected? (not (connected? owner)))
      (do
        (reset! (:page-ref state) nil)
        (await (recycle-browser state))
        false)
      (if-let [page @(:page-ref state)]
        (do
          (reset! (:page-ref state) nil)
          (try
            (await ((get-in state [:services :close-page]) page))
            true
            (catch :default cause
              (record-failure state nil :browser-close (cause-evidence cause :browser-close))
              (await (recycle-browser state))
              false)))
        true))))

(defn- ^:async ensure-browser
  [state]
  (or @(:owner-ref state)
      (let [opts    (:opts state)
            options (cond-> {:startup-timeout-ms (:startup-timeout-ms opts)
                             :signal (:signal state)}
                      (some? @(:launch-deadline state))
                      (assoc :deadline @(:launch-deadline state)))
            owner   (await ((get-in state [:services :launch]) options))]
        (reset! (:owner-ref state) owner)
        (swap! (:run-ref state) assoc-in [:metadata :environment :browser] (:version owner))
        (persist state)
        (write-log state log/debug (str "Browser " (:version owner)))
        owner)))

(defn- ^:async open-page
  [state case]
  (if (interrupted? state)
    :interrupted
    (let [launch (try
                   {:owner (await (ensure-browser state))}
                   (catch :default cause {:error cause}))]
      (if-let [cause (:error launch)]
        (if (interrupted? state)
          :interrupted
          (do
            (record-failure state (:id case) :browser-launch
                            (cause-evidence cause :browser-launch))
            (reset! (:abort-run? state) true)
            :abort))
        (let [opened (try
                       {:page-owner (await ((get-in state [:services :new-page])
                                            (:owner launch) @(:host-ref state)
                                            {:viewport (declarations/resolve-viewport (:view case))
                                             :startup-timeout-ms (:startup-timeout-ms (:opts state))
                                             :signal (:signal state)}))}
                       (catch :default cause {:error cause}))]
          (if-let [cause (:error opened)]
            (if (interrupted? state)
              :interrupted
              (do
                (record-failure state (:id case) :browser-page
                                (cause-evidence cause :browser-page))
                (await (recycle-browser state))
                :abandon))
            (do
              (reset! (:page-ref state) (:page-owner opened))
              (:page (:page-owner opened)))))))))

(defn- ^:async run-fresh-slot
  [state case page warmup? final?]
  (let [opts     (:opts state)
        deadline (process/deadline (:timeout-ms opts))
        case-id  (:id case)
        prepared (try
                   {:response (await (prepare-case state case page deadline))}
                   (catch :default cause {:error cause}))]
    (if-let [cause (:error prepared)]
      (let [evidence (cause-evidence cause :preparation)]
        (record-attempt state (measurement/attempt case-id nil warmup? evidence))
        (when final? (record-screenshot-skip state case-id :no-completed-measured-attempt))
        (if (interrupted? state) :interrupted :failed))
      (let [response (:response prepared)]
        (if (= "ok" (:status response))
          (let [preparation (:preparation response)
                prep-id     (:preparation-id preparation)]
            (record-preparation state preparation)
            (let [attempted (try
                              {:evidence (await (run-attempt state page prep-id deadline))}
                              (catch :default cause {:error cause}))]
              (if-let [cause (:error attempted)]
                (let [evidence (cause-evidence cause :attempt)]
                  (record-attempt state
                                  (measurement/attempt case-id prep-id warmup? evidence))
                  (when final?
                    (record-screenshot-skip state case-id :no-completed-measured-attempt))
                  (if (interrupted? state) :interrupted :failed))
                (let [attempt (measurement/attempt case-id prep-id warmup?
                                                   (:evidence attempted))]
                  (record-attempt state attempt)
                  (if (= :completed (:outcome attempt))
                    (do
                      (when final? (await (screenshot-case state case page)))
                      :ok)
                    (do
                      (when final?
                        (record-screenshot-skip state case-id
                                                :no-completed-measured-attempt))
                      :failed))))))
          (let [evidence (failure-evidence response :preparation)]
            (record-attempt state (measurement/attempt case-id nil warmup? evidence))
            (when final? (record-screenshot-skip state case-id :no-completed-measured-attempt))
            (if (interrupted? state) :interrupted :failed)))))))

(defn- ^:async run-reuse-slot
  [state case page preparation-id warmup?]
  (let [deadline (process/deadline (:timeout-ms (:opts state)))
        result   (try
                   {:evidence (await (run-attempt state page preparation-id deadline))}
                   (catch :default cause {:error cause}))]
    (if-let [cause (:error result)]
      (let [evidence (cause-evidence cause :attempt)]
        (record-attempt state
                        (measurement/attempt (:id case) preparation-id warmup? evidence))
        (if (interrupted? state) :interrupted :failed))
      (let [attempt (measurement/attempt (:id case) preparation-id warmup?
                                         (:evidence result))]
        (record-attempt state attempt)
        (if (= :completed (:outcome attempt)) :ok :failed)))))

(defn- ^:async run-fresh-case
  [state case]
  (let [slots (case-slots (:opts state))
        total (count slots)]
    (loop [index 0]
      (if (>= index total)
        (do
          (ensure-screenshot-status state case :case-abandoned)
          :done)
        (let [page (await (open-page state case))]
          (cond
            (= :interrupted page)
            (do
              (ensure-screenshot-status state case :case-abandoned)
              :interrupted)

            (= :abort page)
            (do
              (ensure-screenshot-status state case :case-abandoned)
              :abort)

            (= :abandon page)
            (do
              (ensure-screenshot-status state case :case-abandoned)
              :abandon)

            (nil? page)
            (do
              (ensure-screenshot-status state case :case-abandoned)
              :abandon)

            :else
            (let [final?  (= index (dec total))
                  outcome (await (run-fresh-slot state case page (nth slots index) final?))
                  closed? (await (close-current-page state))]
              (cond
                (= :interrupted outcome)
                (do
                  (ensure-screenshot-status state case :case-abandoned)
                  :interrupted)

                (not closed?)
                (do
                  (ensure-screenshot-status state case :case-abandoned)
                  :abandon)

                :else
                (recur (inc index))))))))))

(defn- ^:async run-reuse-case
  [state case]
  (let [page (await (open-page state case))]
    (cond
      (= :interrupted page)
      (do (ensure-screenshot-status state case :case-abandoned) :interrupted)

      (= :abort page) :abort

      (= :abandon page)
      (do (ensure-screenshot-status state case :case-abandoned) :abandon)

      (nil? page)
      (do (ensure-screenshot-status state case :case-abandoned) :abandon)

      :else
      (let [opts     (:opts state)
            deadline (process/deadline (:timeout-ms opts))
            prepared (try
                       {:response (await (prepare-case state case page deadline))}
                       (catch :default cause {:error cause}))]
        (if (or (:error prepared)
                (not= "ok" (:status (:response prepared))))
          (do
            (record-failure state (:id case) :preparation
                            (if-let [cause (:error prepared)]
                              (cause-evidence cause :preparation)
                              (failure-evidence (:response prepared) :preparation)))
            (ensure-screenshot-status state case :no-completed-measured-attempt)
            (await (close-current-page state))
            (if (interrupted? state) :interrupted :abandon))
          (let [preparation (:preparation (:response prepared))
                prep-id     (:preparation-id preparation)
                slots       (case-slots opts)
                total       (count slots)]
            (record-preparation state preparation)
            (let [outcome
                  (loop [index 0]
                    (if (>= index total)
                      :done
                      (let [warmup? (nth slots index)
                            result  (await (run-reuse-slot state case page prep-id warmup?))]
                        (cond
                          (= :ok result)
                          (if (= index (dec total))
                            (do (await (screenshot-case state case page)) :done)
                            (recur (inc index)))

                          (= index (dec total))
                          (do
                            (record-screenshot-skip state (:id case)
                                                    :no-completed-measured-attempt)
                            result)

                          :else result))))
                  closed? (await (close-current-page state))]
              (ensure-screenshot-status state case :case-abandoned)
              (cond
                (not closed?) :abandon
                (= outcome :interrupted) :interrupted
                :else outcome))))))))

(defn- ^:async launch-initial
  [state]
  (let [launched (try
                   (await (ensure-browser state))
                   true
                   (catch :default cause
                     (if (interrupted? state)
                       false
                       (do
                         (record-failure state nil :browser-launch
                                         (cause-evidence cause :browser-launch))
                         false))))]
    (reset! (:launch-deadline state) nil)
    launched))

(defn- ^:async run-session
  [state]
  (let [opts             (:opts state)
        startup-deadline (process/deadline (:startup-timeout-ms opts))]
    (reset! (:launch-deadline state) startup-deadline)
    (try
      (let [git (await (build/git-provenance (:root state)
                                             {:deadline startup-deadline
                                              :signal (:signal state)}))]
        (swap! (:run-ref state) assoc-in [:metadata :diagnostics :runner-git] git)
        (persist state))
      (catch :default cause
        (if (interrupted? state)
          (throw cause)
          (do
            (record-failure state nil :runner-git (cause-evidence cause :runner-git))
            (reset! (:abort-run? state) true)))))
    (when-not @(:abort-run? state)
      (try
        (let [remaining (process/remaining startup-deadline)]
          (when (zero? remaining)
            (throw (ex-info "server startup timed out"
                            {:type ::startup-timeout :phase :server-startup})))
          (reset! (:host-ref state)
                  (await (server/open (:selected-build state)
                                      {:startup-timeout-ms remaining
                                       :signal (:signal state)}))))
        (catch :default cause
          (if (interrupted? state)
            (throw cause)
            (do
              (record-failure state nil :server-startup
                              (cause-evidence cause :server-startup))
              (reset! (:abort-run? state) true))))))
    (when (and (not @(:abort-run? state)) (not (interrupted? state)))
      (when-not (await (launch-initial state))
        (reset! (:abort-run? state) true)))
    (when (and (not @(:abort-run? state)) (not (interrupted? state)))
      (loop [cases (:cases state)]
        (when (and (seq cases)
                   (not @(:abort-run? state))
                   (not (interrupted? state)))
          (let [case (first cases)]
            (write-log state log/info (str "▸ Case " (result/case-name (:id case))
                                           " (" (name (:context case)) ")"))
            (let [outcome (if (= :fresh (:context case))
                            (await (run-fresh-case state case))
                            (await (run-reuse-case state case)))]
              (when (#{:abort :interrupted} outcome)
                (reset! (:abort-run? state) true))
              (recur (rest cases)))))))))

(defn- finish-run
  [state]
  (let [run           @(:run-ref state)
        interrupted   (interruption (:signal state))
        all-attempted? (every? (fn [[_ slots]]
                                 (and (zero? (:warmups slots))
                                      (zero? (:repetitions slots))))
                               (:remaining run))
        failed?       (some #(= :failed (:outcome %)) (:attempts run))
        reason        (cond
                        interrupted :interrupted
                        all-attempted? (if failed? :failed :completed)
                        :else :incomplete)
        failures      (get-in run [:metadata :diagnostics :failures])
        attempt-failure (some #(when (:failure %) (:failure %))
                              (reverse (:attempts run)))
        termination   (cond-> {:reason reason}
                        interrupted
                        (assoc :signal (:signal interrupted))

                        (and (seq failures)
                             (or (not= :completed reason)
                                 @(:harness-failed? state)))
                        (assoc :failure (last failures))

                        (and (not= :completed reason)
                             (empty? failures)
                             (some? attempt-failure))
                        (assoc :failure attempt-failure))]
    (when (:screenshots? (:opts state))
      (swap! (:run-ref state) update-in [:metadata :diagnostics :screenshots]
             (fn [screenshots]
               (reduce (fn [saved case]
                         (let [name (result/case-name (:id case))]
                           (if (contains? saved name)
                             saved
                             (assoc saved name {:status :skipped :reason :not-run}))))
                       (or screenshots {})
                       (:cases state)))))
    (swap! (:run-ref state) result/finish-run (now) termination)
    (persist state)
    @(:run-ref state)))

(defn- ^:async cleanup
  [state]
  (let [page-failure
        (try
          (when-let [page @(:page-ref state)]
            (reset! (:page-ref state) nil)
            (await ((get-in state [:services :close-page]) page)))
          nil
          (catch :default cause cause))
        browser-failure
        (try
          (when-let [owner @(:owner-ref state)]
            (reset! (:owner-ref state) nil)
            (await ((get-in state [:services :close]) owner)))
          nil
          (catch :default cause cause))
        server-failure
        (try
          (when-let [host @(:host-ref state)]
            (reset! (:host-ref state) nil)
            (await (server/close host)))
          nil
          (catch :default cause cause))
        failure (or page-failure browser-failure server-failure)]
    (when failure
      (warn-safely state (str "run: cleanup failed: " (message-of failure) "\n")))
    failure))

(defn- remove-build
  [root selected-build]
  (artifacts/remove-builds root [(:id selected-build)]))

(defn- git-facts
  [selected-build]
  (merge {:sha nil :dirty nil}
         (get-in selected-build [:manifest :provenance :git])))

(defn- playwright-version
  [root]
  (try
    (let [require-package (if (exists? js/require)
                            js/require
                            (node-module/createRequire (path/join root "package.json")))]
      (.-version (require-package "playwright/package.json")))
    (catch :default _ nil)))

(defn- environment
  [root _selected-build]
  (let [cpus (array-seq (os/cpus))]
    {:node (.-version js/process)
     :playwright (playwright-version root)
     :browser nil
     :platform (os/platform)
     :release (os/release)
     :arch (os/arch)
     :cpu {:models (vec (distinct (map #(.-model %) cpus)))
           :logical-count (count cpus)}
     :launch browser/launch-settings}))

(defn- metadata
  [root selected-build opts]
  {:git (git-facts selected-build)
   :build (cond-> {:functional (or (get-in selected-build [:manifest :functional]) {})
                   :provenance (assoc (get-in selected-build [:manifest :provenance])
                                      :build-id (:id selected-build))}
            (contains? (:manifest selected-build) :environment)
            (assoc :environment (get-in selected-build [:manifest :environment])))
   :environment (environment root selected-build)
   :scored? true
   :diagnostics {:seed (:seed opts)
                 :filter (:filter opts)
                 :warmups (:warmups opts)
                 :repetitions (:repetitions opts)
                 :timeout-ms (:timeout-ms opts)
                 :build-timeout-ms (:build-timeout-ms opts)
                 :startup-timeout-ms (:startup-timeout-ms opts)
                 :screenshots? (boolean (:screenshots? opts))
                 :runner-git {:sha nil :dirty nil}
                 :runtime-helper-identity :unverified
                 :screenshots {}
                 :failures []}})

(defn- normalize-options
  [options]
  (-> options
      (assoc :screenshots? (boolean (or (:screenshots? options)
                                        (:screenshots options)))
             :remove? (boolean (or (:remove? options)
                                   (:remove options))))
      (dissoc :screenshots :remove)))

(defn ^:async execute
  "Executes a prepared selection in an allocated home and owns host and browser.

  Options are resolved CLI options. root is the frontend checkout. A browser
  startup failure leaves a valid partial record. Returns the run, home, report,
  log path, exit code and optional error/storage-error. A storage error reports any
  failed record or log write, including a failure followed by a successful write."
  [root home selected-build cases options signal]
  (binding [log/*log-path* (:log-path home)]
    (let [opts  (assoc (normalize-options options) :run-id (:run-id home))
          run   (result/create-run
                 {:run-id (:run-id opts)
                  :started-at (now)
                  :plan {:cases cases
                         :warmups (:warmups opts)
                         :repetitions (:repetitions opts)
                         :timeout-policy {:attempt-ms (:timeout-ms opts)}}
                  :metadata (metadata root selected-build opts)})
          state {:root root
                 :selected-build selected-build
                 :cases cases
                 :opts opts
                 :signal signal
                 :home home
                 :run-ref (atom run)
                 :storage-error-ref (atom nil)
                 :owner-ref (atom nil)
                 :page-ref (atom nil)
                 :host-ref (atom nil)
                 :launch-deadline (atom nil)
                 :abort-run? (atom false)
                 :harness-failed? (atom false)
                 :services (merge browser/service (:browser opts))}]
      (persist state)
      (let [error (atom nil)]
        (try
          (await (run-session state))
          (catch :default cause
            (reset! error cause)
            (when-not (interrupted? state)
              (try
                (record-failure state nil :runner (cause-evidence cause :runner))
                (catch :default record-cause
                  (warn-safely state (str "Could not record failure: "
                                          (message-of record-cause) "\n")))))))
        (when-let [cleanup-error (await (cleanup state))]
          (reset! error cleanup-error)
          (try
            (record-failure state nil :cleanup
                            (cause-evidence cleanup-error :cleanup))
            (catch :default record-cause
              (warn-safely state (str "Could not record cleanup failure: "
                                      (message-of record-cause) "\n")))))
        (let [saved-run     (try
                              (finish-run state)
                              (catch :default cause
                                (reset! error cause)
                                nil))
              run           (or saved-run @(:run-ref state))
              removal-error (when (and (:remove? opts) saved-run)
                              (try
                                (remove-build root selected-build)
                                nil
                                (catch :default cause cause)))
              report-result (try
                              {:report (format/format-run (summary/summarize run))}
                              (catch :default cause
                                (warn-safely state (str "Report failed: "
                                                        (message-of cause) "\n"))
                                {:error cause}))
              log-error     (try
                              (write-log state log/debug
                                         (str "Run " (name (get-in run [:termination :reason]))))
                              nil
                              (catch :default cause cause))]
          (when-let [cause (or removal-error (:error report-result) log-error)]
            (compare-and-set! error nil cause))
          (let [reason      (get-in run [:termination :reason])
                signal-exit (if (= "SIGTERM" (get-in run [:termination :signal]))
                              143 130)
                base        (cond
                              (= :completed reason) 0
                              (= :interrupted reason) signal-exit
                              :else 1)
                exit        (if (= :interrupted reason)
                              base
                              (if (or @error
                                      @(:storage-error-ref state)
                                      (and (= :completed reason)
                                           @(:harness-failed? state)))
                                1
                                base))]
            {:run run
             :home home
             :log-path (:log-path home)
             :report (:report report-result)
             :exit-code exit
             :error @error
             :storage-error @(:storage-error-ref state)}))))))

(defn ^:async run
  "Prepares or loads a build, collects matching cases and executes one run.

  The output home is reserved before build work so filesystem errors fail fast.
  A build or catalog failure leaves the home without a run record and rethrows
  with build facts."
  [root options signal]
  (let [opts (normalize-options options)
        home (runs/create-home {:root root :run-id (str (random-uuid))
                                :output-dir (:output-dir opts) :remove (:remove? opts)})]
    (binding [log/*log-path* (:log-path home)]
      (try
        (log/result (str "Run " (:run-id home)))
        (log/result (str "Output " (:dir home)))
        (let [selected-build
              (if-let [id (:build-id opts)]
                (artifacts/load-build root id)
                (await (build/prepare
                        {:root root
                         :features {:default-features? (:default-features opts true)
                                    :features (or (:features opts) [])}
                         :build-timeout-ms (:build-timeout-ms opts)
                         :signal signal})))
              cases (try
                      (catalog/collect selected-build
                                       {:master-seed (:seed opts)
                                        :filter (:filter opts)})
                      (catch :default cause
                        (throw (ex-info (message-of cause)
                                        (assoc (ex-data cause)
                                               :build-id (:id selected-build)
                                               :log-path (:log-path selected-build))
                                        cause))))]
          (log/info (str "  build " (:id selected-build)))
          (log/info (str "  selected " (count cases) " cases"))
          (let [outcome (await (execute root home selected-build cases opts signal))]
            (try
              (when-let [report (:report outcome)]
                (log/result (str "Report\n" report)))
              outcome
              (catch :default cause
                (assoc outcome :error (or (:error outcome) cause)
                       :storage-error cause
                       :exit-code (if (= :interrupted (get-in outcome [:run :termination :reason]))
                                    (:exit-code outcome) 1))))))
        (catch :default cause
          (throw (ex-info (message-of cause)
                          (assoc (ex-data cause) :log-path
                                 (or (:log-path (ex-data cause)) (:log-path home)))
                          cause)))))))

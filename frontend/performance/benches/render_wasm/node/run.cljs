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
  (:require-macros
   [app.common.exceptions :as ex])
  (:require
   ["module" :as node-module]
   ["os" :as os]
   ["path" :as path]
   [app.common.schema :as sm]
   [app.common.transit :as transit]
   [benches.render-wasm.declarations :as declarations]
   [benches.render-wasm.errors :as errors :include-macros true :refer [attempt failure?]]
   [benches.render-wasm.failures :as failures]
   [benches.render-wasm.measurement :as measurement]
   [benches.render-wasm.node.artifacts :as artifacts]
   [benches.render-wasm.node.browser :as browser]
   [benches.render-wasm.node.build :as build]
   [benches.render-wasm.node.catalog :as catalog]
   [benches.render-wasm.node.log :as log]
   [benches.render-wasm.node.outcome :as outcome]
   [benches.render-wasm.node.process :as process]
   [benches.render-wasm.node.runs :as runs]
   [benches.render-wasm.node.server :as server]
   [benches.render-wasm.report.format :as format]
   [benches.render-wasm.report.summarize :as summary]
   [benches.render-wasm.result :as result]
   [clojure.string :as str]))

(def schema:run-options
  "Resolved options for run preparation and execution."
  (let [timeout-ms [:int {:min 1 :max 2147483647}]]
    [:map {:closed true}
     [:seed [:int {:min 0 :max 4294967295}]]
     [:warmups [:int {:min 0 :max js/Number.MAX_SAFE_INTEGER}]]
     [:repetitions [:int {:min 1 :max js/Number.MAX_SAFE_INTEGER}]]
     [:timeout-ms timeout-ms]
     [:build-timeout-ms timeout-ms]
     [:startup-timeout-ms timeout-ms]
     [:filter {:optional true} [:maybe :string]]
     [:build-id {:optional true} [:string {:min 1}]]
     [:output-dir {:optional true} [:maybe [:string {:min 1}]]]
     [:default-features {:optional true} :boolean]
     [:features {:optional true} [:vector [:string {:min 1}]]]
     [:screenshots {:optional true} :boolean]
     [:remove {:optional true} :boolean]
     [:require-gpu {:optional true} :boolean]
     [:browser {:optional true} [:map-of :keyword fn?]]]))

(def ^:private check-options
  (sm/check-fn schema:run-options
               :hint "invalid run options"
               :type ::invalid-options))

(defn- now
  []
  (.toISOString (js/Date.)))

(defn- cause-evidence
  "Plain failure evidence for a rejected browser, server or persistence action."
  [cause fallback-phase]
  (let [data  (ex-data cause)
        phase (or (:phase data) (name fallback-phase))]
    (cond-> {:status "failed"
             :phase (if (keyword? phase) (name phase) (str phase))
             :message (errors/message-of cause)
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

(defn- keep-sink-failure
  "Remembers the first failed write to the run record or log. The failure stays
  in the outcome even when a later write succeeds."
  [state cause]
  (swap! (:sink-failure-ref state) #(or % cause))
  cause)

(defn- write-log
  [state output text]
  (try
    (output text)
    (catch :default cause
      (throw (keep-sink-failure
              state
              (if (errors/fatal? cause)
                cause
                (errors/fatal-error "cannot write the run log" {} cause)))))))

(defn- warn-safely
  "Appends runner diagnostics without replacing an error already under handling."
  [state text]
  (ex/ignoring (write-log state log/warn text))
  nil)

(defn- persist
  [state]
  (try
    (runs/write-record! (:home state) @(:run-ref state))
    (catch :default cause
      (throw (keep-sink-failure
              state
              (errors/fatal-error "cannot save the run record" {:log-path (get-in state [:home :log-path])} cause))))))

(defn- record-preparation
  [state preparation]
  (swap! (:run-ref state) result/record-preparation preparation)
  (persist state)
  (write-log state log/debug (str "Prepared " (result/case-name (:case-id preparation)))))

(defn- record-attempt
  [state measured]
  (swap! (:run-ref state) result/record-attempt measured)
  (persist state)
  (let [remaining (get-in @(:run-ref state) [:remaining (:case-id measured)])
        total     (+ (:warmups (:opts state)) (:repetitions (:opts state)))
        index     (- total (:warmups remaining) (:repetitions remaining))]
    (write-log state (if (= :failed (:outcome measured)) log/warn log/info)
               (str "  attempt " index "/" total " " (name (:outcome measured))
                    (when (:warmup? measured) " (warmup)")))))

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
  (when (:screenshots (:opts state))
    (when-not (screenshot-status state case-id)
      (record-screenshot-status state case-id {:status :skipped :reason reason}))))

(defn- ensure-screenshot-status
  [state case reason]
  (when (:screenshots (:opts state))
    (when-not (screenshot-status state (:id case))
      (record-screenshot-skip state (:id case) reason))))

(defn- ^:async screenshot-case
  [state case page]
  (when (:screenshots (:opts state))
    (let [{:keys [file path]} (runs/screenshot-path (:home state) (:id case))
          opts   (:opts state)
          bounds {:deadline (process/deadline (:startup-timeout-ms opts))
                  :signal (:signal state) :phase :screenshot}
          shot   (attempt (await ((get-in state [:services :screenshot]) page path bounds)))]
      (if (failure? shot)
        (do
          (record-screenshot-status state (:id case)
                                    {:file file :status :failed
                                     :message (errors/message-of shot)
                                     :cause (failures/describe-cause shot)})
          (write-log state log/warn (str "Screenshot failed for "
                                         (result/case-name (:id case)) ": "
                                         (errors/message-of shot) "\n")))
        (do
          (record-screenshot-status state (:id case) {:file file :status :ok})
          (write-log state log/debug (str "Screenshot " file)))))))

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
  (let [opts     (:opts state)
        build-id (:id (:selected-build state))
        response (attempt
                  (await ((get-in state [:services :prepare])
                          page
                          (transit/encode-str
                           {:case case
                            :preparation-id (str (random-uuid))
                            :timeout-ms (:timeout-ms opts)
                            :module-url (renderer-url build-id "render-wasm.js")
                            :wasm-url (renderer-url build-id "render-wasm.wasm")})
                          {:deadline deadline :signal (:signal state) :phase :preparation})))]
    (cond
      (failure? response) {:failure (cause-evidence response :preparation)}
      (= "ok" (:status response)) {:preparation (:preparation response)}
      :else {:failure (failure-evidence response :preparation)})))

(defn- ^:async run-attempt
  "Returns the bridge evidence of one attempt, or the failure."
  [state page preparation-id deadline]
  (attempt
   (await ((get-in state [:services :run])
           page
           (transit/encode-str
            {:preparation-id preparation-id
             :timeout-ms (max 1 (process/remaining deadline))})
           {:deadline deadline :signal (:signal state) :phase :attempt}))))

(defn- ^:async recycle-browser
  [state]
  (when-let [owner @(:owner-ref state)]
    (reset! (:owner-ref state) nil)
    (let [closed (attempt (await ((get-in state [:services :close]) owner)))]
      (when (failure? closed)
        (write-log state log/warn (str "Browser cleanup failed: " (errors/message-of closed)))))))

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
          (let [closed (attempt (await ((get-in state [:services :close-page]) page)))]
            (if (failure? closed)
              (do
                (record-failure state nil :browser-close (cause-evidence closed :browser-close))
                (await (recycle-browser state))
                false)
              true)))
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

(defn- usable-graphics-identity?
  [value]
  (and (string? value)
       (not (contains? #{"" "unknown" "unavailable" "null" "undefined"}
                       (str/lower-case (str/trim value))))))

(defn- check-gpu
  [state preparation]
  (let [graphics (:effective-graphics preparation)
        accepted (and (true? (:webgl2 graphics))
                      (false? (:software graphics))
                      (usable-graphics-identity? (:vendor graphics))
                      (usable-graphics-identity? (:renderer graphics)))]
    (if (and (:require-gpu (:opts state)) (not accepted))
      (do
        (record-failure state (:case-id preparation) :gpu
                        {:status "failed" :phase "gpu"
                         :message (str "GPU check failed; renderer: " (pr-str (:renderer graphics))
                                       ". Hardware WebGL2 with a usable vendor and renderer is required."
                                       " Start the container with ./manage.sh run-devenv --gpu and use pnpm run render-bench.")
                         :partial {:graphics graphics}})
        (reset! (:abort-run? state) true)
        :abort)
      :ok)))

(defn- ^:async open-case-page
  [state case owner]
  (let [opened (attempt
                (await ((get-in state [:services :new-page])
                        owner @(:host-ref state)
                        {:viewport (declarations/resolve-viewport (:view case))
                         :startup-timeout-ms (:startup-timeout-ms (:opts state))
                         :signal (:signal state)})))]
    (cond
      (not (failure? opened))
      (do
        (reset! (:page-ref state) opened)
        {:page (:page opened)})

      (interrupted? state)
      {:outcome :interrupted}

      :else
      (do
        (record-failure state (:id case) :browser-page (cause-evidence opened :browser-page))
        (await (recycle-browser state))
        {:outcome :abandon}))))

(defn- ^:async open-page
  [state case]
  (if (interrupted? state)
    {:outcome :interrupted}
    (let [owner (attempt (await (ensure-browser state)))]
      (cond
        (not (failure? owner))
        (await (open-case-page state case owner))

        (interrupted? state)
        {:outcome :interrupted}

        :else
        (do
          (record-failure state (:id case) :browser-launch
                          (cause-evidence owner :browser-launch))
          (reset! (:abort-run? state) true)
          {:outcome :abort})))))

(defn- accept-preparation
  [state preparation]
  (record-preparation state preparation)
  (check-gpu state preparation))

(defn- ^:async execute-slot
  [state case page preparation-id warmup? deadline]
  (let [response (await (run-attempt state page preparation-id deadline))
        measured (measurement/attempt (:id case) preparation-id warmup?
                                      (if (failure? response)
                                        (cause-evidence response :attempt)
                                        response))]
    (record-attempt state measured)
    (cond
      (and (failure? response) (interrupted? state)) :interrupted
      (= :completed (:outcome measured)) :ok
      :else :failed)))

(defn- ^:async finish-slot
  [state case page outcome final?]
  (when final?
    (if (= :ok outcome)
      (await (screenshot-case state case page))
      (record-screenshot-skip state (:id case) :no-completed-measured-attempt)))
  outcome)

(defn- ^:async run-fresh-slot
  [state case page warmup? final?]
  (let [deadline (process/deadline (:timeout-ms (:opts state)))
        prepared (await (prepare-case state case page deadline))]
    (if-let [evidence (:failure prepared)]
      (do
        (record-attempt state (measurement/attempt (:id case) nil warmup? evidence))
        (await (finish-slot state case page
                            (if (interrupted? state) :interrupted :failed) final?)))
      (let [preparation (:preparation prepared)]
        (if (= :abort (accept-preparation state preparation))
          :abort
          (let [outcome (await (execute-slot state case page (:preparation-id preparation)
                                             warmup? deadline))]
            (await (finish-slot state case page outcome final?))))))))

(defn- ^:async run-fresh-page
  [state case warmup? final?]
  (let [{:keys [page outcome]} (await (open-page state case))]
    (if page
      (let [outcome (await (run-fresh-slot state case page warmup? final?))
            closed? (await (close-current-page state))]
        (cond
          (#{:abort :interrupted} outcome) outcome
          (not closed?) :abandon
          :else outcome))
      (or outcome :abandon))))

(defn- ^:async run-fresh-slots
  [state case slots]
  (if-let [[warmup? & pending] (seq slots)]
    (let [outcome (await (run-fresh-page state case warmup? (empty? pending)))]
      (if (#{:ok :failed} outcome)
        (run-fresh-slots state case pending)
        outcome))
    :done))

(defn- ^:async run-fresh-case
  [state case]
  (let [outcome (await (run-fresh-slots state case (case-slots (:opts state))))]
    (ensure-screenshot-status state case :case-abandoned)
    outcome))

(defn- ^:async run-reuse-slots
  [state case page preparation-id slots]
  (if-let [[warmup? & pending] (seq slots)]
    (let [deadline (process/deadline (:timeout-ms (:opts state)))
          outcome  (await (execute-slot state case page preparation-id warmup? deadline))]
      (await (finish-slot state case page outcome (empty? pending)))
      (if (= :ok outcome)
        (run-reuse-slots state case page preparation-id pending)
        outcome))
    :done))

(defn- ^:async abandon-preparation
  [state case evidence]
  (record-failure state (:id case) :preparation evidence)
  (ensure-screenshot-status state case :no-completed-measured-attempt)
  (await (close-current-page state))
  (if (interrupted? state) :interrupted :abandon))

(defn- ^:async run-reuse-prepared
  [state case page preparation]
  (let [accepted (accept-preparation state preparation)
        outcome  (if (= :abort accepted)
                   :abort
                   (await (run-reuse-slots state case page (:preparation-id preparation)
                                           (case-slots (:opts state)))))
        closed?  (await (close-current-page state))]
    (ensure-screenshot-status state case :case-abandoned)
    (if closed? outcome :abandon)))

(defn- ^:async run-reuse-page
  [state case page]
  (let [deadline (process/deadline (:timeout-ms (:opts state)))
        prepared (await (prepare-case state case page deadline))]
    (if-let [evidence (:failure prepared)]
      (await (abandon-preparation state case evidence))
      (await (run-reuse-prepared state case page (:preparation prepared))))))

(defn- ^:async run-reuse-case
  [state case]
  (let [{:keys [page outcome]} (await (open-page state case))]
    (cond
      page (await (run-reuse-page state case page))
      (= :abort outcome) :abort

      :else
      (do
        (ensure-screenshot-status state case :case-abandoned)
        (or outcome :abandon)))))


(defn- ^:async launch-initial
  [state]
  (let [owner (attempt (await (ensure-browser state)))]
    (reset! (:launch-deadline state) nil)
    (when (failure? owner)
      (when-not (interrupted? state)
        (record-failure state nil :browser-launch (cause-evidence owner :browser-launch)))
      (reset! (:abort-run? state) true))
    (not (failure? owner))))

(defn- fail-startup
  "Records the startup failure and aborts the run. An interrupted run records
  nothing. Returns false."
  [state stage cause]
  (when-not (interrupted? state)
    (record-failure state nil stage (cause-evidence cause stage))
    (reset! (:abort-run? state) true))
  false)

(defn- ^:async record-provenance
  [state deadline]
  (let [git (attempt (await (build/git-provenance (:root state)
                                                  {:deadline deadline :signal (:signal state)})))]
    (if (failure? git)
      (fail-startup state :runner-git git)
      (do
        (swap! (:run-ref state) assoc-in [:metadata :diagnostics :runner-git] git)
        (persist state)
        true))))

(defn- ^:async start-server
  [state deadline]
  (let [remaining (process/remaining deadline)
        host      (attempt
                   (when (zero? remaining)
                     (throw (ex-info "server startup timed out"
                                     {:type ::startup-timeout :phase :server-startup})))
                   (await (server/open (:selected-build state)
                                       {:startup-timeout-ms remaining :signal (:signal state)})))]
    (if (failure? host)
      (fail-startup state :server-startup host)
      (do
        (reset! (:host-ref state) host)
        true))))

(defn- session-active?
  [state]
  (and (not @(:abort-run? state)) (not (interrupted? state))))

(defn- ^:async run-cases
  [state cases]
  (when (and (seq cases) (session-active? state))
    (let [case (first cases)]
      (write-log state log/info (str "▸ Case " (result/case-name (:id case))
                                     " (" (name (:context case)) ")"))
      (let [outcome (if (= :fresh (:context case))
                      (await (run-fresh-case state case))
                      (await (run-reuse-case state case)))]
        (if (#{:abort :interrupted} outcome)
          (do
            (reset! (:abort-run? state) true)
            outcome)
          (run-cases state (rest cases)))))))

(defn- ^:async run-session
  [state]
  (let [deadline (process/deadline (:startup-timeout-ms (:opts state)))]
    (reset! (:launch-deadline state) deadline)
    (let [provenance-ready? (await (record-provenance state deadline))
          server-ready?     (and provenance-ready? (await (start-server state deadline)))
          browser-ready?    (and server-ready? (session-active? state) (await (launch-initial state)))]
      (when (and browser-ready? (session-active? state))
        (await (run-cases state (:cases state)))))))

(defn- get-termination
  [run interrupted harness-failed?]
  (let [all-attempted? (every? (fn [[_ slots]]
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
                             (or (not= :completed reason) harness-failed?))
                        (assoc :failure (last failures))

                        (and (not= :completed reason)
                             (empty? failures)
                             (some? attempt-failure))
                        (assoc :failure attempt-failure))]
    termination))

(defn- complete-screenshots
  [screenshots cases]
  (let [defaults (into {} (map (fn [case]
                                 [(result/case-name (:id case)) {:status :skipped :reason :not-run}]))
                       cases)]
    (merge defaults screenshots)))

(defn- finish-run
  [state]
  (let [termination (get-termination @(:run-ref state) (interruption (:signal state))
                                     @(:harness-failed? state))]
    (when (:screenshots (:opts state))
      (swap! (:run-ref state) update-in [:metadata :diagnostics :screenshots]
             complete-screenshots (:cases state)))
    (swap! (:run-ref state) result/finish-run (now) termination)
    (persist state)
    @(:run-ref state)))

(defn- ^:async release-resource
  "Closes the resource in `reference`. Returns nil or the failure."
  [reference close]
  (attempt :catch-fatal
           (when-let [resource @reference]
             (reset! reference nil)
             (await (close resource)))
           nil))

(defn- ^:async cleanup
  [state]
  (let [page-failure    (await (release-resource (:page-ref state) (get-in state [:services :close-page])))
        browser-failure (await (release-resource (:owner-ref state) (get-in state [:services :close])))
        server-failure  (await (release-resource (:host-ref state) server/close))
        failure         (or page-failure browser-failure server-failure)]
    (when failure
      (warn-safely state (str "run: cleanup failed: " (errors/message-of failure) "\n")))
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
  (ex/ignoring
   (let [require-package (if (exists? js/require)
                           js/require
                           (node-module/createRequire (path/join root "package.json")))]
     (.-version (require-package "playwright/package.json")))))

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
     :launch (browser/launch-settings)}))

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
                 :screenshots? (boolean (:screenshots opts))
                 :require-gpu? (boolean (:require-gpu opts))
                 :penpot-gpu (or (.-PENPOT_GPU (.-env js/process)) nil)
                 :runner-git {:sha nil :dirty nil}
                 :runtime-helper-identity :unverified
                 :screenshots {}
                 :failures []}})

(defn- create-run-record
  [root run-id selected-build cases opts]
  (result/create-run
   {:run-id run-id
    :started-at (now)
    :plan {:cases cases
           :warmups (:warmups opts)
           :repetitions (:repetitions opts)
           :timeout-policy {:attempt-ms (:timeout-ms opts)}}
    :metadata (metadata root selected-build opts)}))

(defn- create-run-state
  [root home selected-build cases opts signal]
  {:root root
   :selected-build selected-build
   :cases cases
   :opts opts
   :signal signal
   :home home
   :run-ref (atom (create-run-record root (:run-id home) selected-build cases opts))
   :sink-failure-ref (atom nil)
   :owner-ref (atom nil)
   :page-ref (atom nil)
   :host-ref (atom nil)
   :launch-deadline (atom nil)
   :abort-run? (atom false)
   :harness-failed? (atom false)
   :services (merge browser/service (:browser opts))})

(defn- record-session-failure
  [state stage cause]
  (let [recorded (attempt :catch-fatal
                          (record-failure state nil stage (cause-evidence cause stage)))]
    (when (failure? recorded)
      (warn-safely state (str "Could not record " (when (= :cleanup stage) "cleanup ")
                              "failure: " (errors/message-of recorded) "\n")))))

(defn- ^:async execute-session
  "Runs the session. Returns the failure that ended it, or nil. An interruption
  is not a failure."
  [state]
  (let [ended (attempt :catch-fatal (await (run-session state)))]
    (when (and (failure? ended) (not (interrupted? state)))
      (record-session-failure state :runner ended)
      ended)))

(defn- ^:async cleanup-session
  [state]
  (when-let [cause (await (cleanup state))]
    (record-session-failure state :cleanup cause)
    cause))

(defn- remove-run-build
  [state saved?]
  (when (and saved? (:remove (:opts state)))
    (attempt :catch-fatal
             (remove-build (:root state) (:selected-build state))
             nil)))

(defn- format-run-report
  "Returns the report text or the failure."
  [state run]
  (let [report (attempt (format/format-run (summary/summarize run)))]
    (when (failure? report)
      (warn-safely state (str "Report failed: " (errors/message-of report) "\n")))
    report))

(defn- log-run-completion
  [state run]
  (attempt :catch-fatal
           (write-log state log/debug (str "Run " (name (get-in run [:termination :reason]))))
           nil))

(defn- get-exit-code
  [run causes harness-failed?]
  (case (get-in run [:termination :reason])
    :interrupted (outcome/interruption-code (get-in run [:termination :signal]))
    :completed (if (or (seq causes) harness-failed?) 1 0)
    1))

(defn- finish-execution
  [state session-error cleanup-error]
  (let [saved   (attempt :catch-fatal (finish-run state))
        run     (if (failure? saved) @(:run-ref state) saved)
        removal (remove-run-build state (not (failure? saved)))
        report  (format-run-report state run)
        logged  (log-run-completion state run)
        causes  (->> [cleanup-error session-error saved removal report logged
                      @(:sink-failure-ref state)]
                     (filter failure?)
                     distinct
                     vec)
        home    (:home state)]
    {:run run
     :home home
     :log-path (:log-path home)
     :report (when-not (failure? report) report)
     :errors causes
     :exit-code (get-exit-code run causes @(:harness-failed? state))}))

(defn ^:async execute
  "Executes a prepared selection in an allocated home and owns host and browser.

  - `root`: the frontend checkout root.
  - `home`: the reserved output home.
  - `selected-build`: the prepared build.
  - `cases`: the selected cases.
  - `options`: resolved options satisfying `schema:run-options`.
  - `signal`: an optional abort signal.

  A browser startup failure leaves a valid partial record.

  Returns the run, home, report, log path, exit code and the failures in
  :errors, a cleanup failure first. :errors includes any failed record or log
  write, including a failure followed by a successful write. An interruption
  is not a failure."
  [root home selected-build cases options signal]
  (check-options options)
  (binding [log/*log-path* (:log-path home)]
    (let [state (create-run-state root home selected-build cases options signal)]
      (persist state)
      (let [session-error (await (execute-session state))
            cleanup-error (await (cleanup-session state))]
        (finish-execution state session-error cleanup-error)))))

(defn- ^:async get-selected-build
  [root opts signal]
  (if-let [id (:build-id opts)]
    (artifacts/load-build root id)
    (await (build/prepare
            {:root root
             :features {:default-features? (:default-features opts true)
                        :features (or (:features opts) [])}
             :build-timeout-ms (:build-timeout-ms opts)
             :signal signal}))))

(defn- get-cases
  [selected-build opts]
  (try
    (catalog/collect selected-build {:master-seed (:seed opts) :filter (:filter opts)})
    (catch :default cause
      (throw (ex-info (errors/message-of cause)
                      (assoc (ex-data cause) :build-id (:id selected-build)
                             :log-path (:log-path selected-build))
                      cause)))))

(defn- publish-report
  [result]
  (outcome/publish result
                   #(when-let [report (:report result)]
                      (log/result (str "Report\n" report)))))

(defn ^:async run
  "Prepares or loads a build, collects matching cases and executes one run.

  - `root`: the frontend checkout root.
  - `options`: resolved options satisfying `schema:run-options`.
  - `signal`: an optional abort signal.

  Validates options before allocating output. Returns the execution result.
  A build or catalog failure throws with the run log path and build facts."
  [root options signal]
  (let [opts (check-options options)
        home (runs/create-home {:root root :run-id (str (random-uuid))
                                :output-dir (:output-dir opts) :remove (:remove opts)})]
    (binding [log/*log-path* (:log-path home)]
      (try
        (log/result (str "Run " (:run-id home)))
        (log/result (str "Output " (:dir home)))
        (let [selected-build (await (get-selected-build root opts signal))
              cases          (get-cases selected-build opts)]
          (log/info (str "  build " (:id selected-build)))
          (log/info (str "  selected " (count cases) " cases"))
          (publish-report (await (execute root home selected-build cases opts signal))))
        (catch :default cause
          (throw (-> cause
                     errors/as-failure
                     (errors/with-data {:log-path (:log-path home)}))))))))

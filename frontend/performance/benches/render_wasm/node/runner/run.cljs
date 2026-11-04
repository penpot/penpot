;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.runner.run
  "Renderer benchmark runs over fixed builds.

  Runs are stored in a `home`, and execute via a loopback host and at most one
  Chromium process.

  `:fresh` cases prepare once per slot on a new page and context.
  `:reuse` cases prepare once and run every slot on the same page and context.

  A failed attempt consumes its slot.

  Browser failures leave the current case's remaining slots unattempted and
  continue with the next case in a new browser."
  (:require-macros
   [app.common.exceptions :as ex])
  (:require
   ["module" :as node-module]
   ["os" :as os]
   ["path" :as path]
   [app.common.transit :as transit]
   [benches.render-wasm.declarations :as declarations]
   [benches.render-wasm.errors :as errors :refer [attempt failure?]]
   [benches.render-wasm.measurement :as measurement]
   [benches.render-wasm.node.catalog.loader :as catalog]
   [benches.render-wasm.node.log :as log]
   [benches.render-wasm.node.outcome :as outcome]
   [benches.render-wasm.node.process :as process]
   [benches.render-wasm.node.runner.browser :as browser]
   [benches.render-wasm.node.runner.build :as build]
   [benches.render-wasm.node.runner.server :as server]
   [benches.render-wasm.node.storage.artifacts :as artifacts]
   [benches.render-wasm.node.storage.runs :as runs]
   [benches.render-wasm.node.time :as time]
   [benches.render-wasm.report.format :as format]
   [benches.render-wasm.report.summarize :as summary]
   [benches.render-wasm.result :as result]
   [clojure.string :as str]))

(defn get-execution-options
  "Returns resolved settings for executing a prepared run."
  [options]
  (select-keys options [:seed :filter :warmups :repetitions :timeout-ms
                        :startup-timeout-ms :screenshots :require-gpu :remove]))

(defn- cause-evidence
  "Plain failure evidence for a rejected browser, server or persistence action."
  [cause fallback-phase]
  (cond-> (errors/fail-data
           (errors/evidence (or (:phase (ex-data cause)) fallback-phase) cause))
    (contains? (ex-data cause) ::browser/deadline-exceeded)
    (assoc :deadline-exceeded (::browser/deadline-exceeded (ex-data cause)))))

(defn- failure-evidence
  "Projects a bridge failure response into diagnostics evidence."
  [evidence fallback-phase]
  (if (map? evidence)
    (cond-> (select-keys evidence [:status :phase :message :cause :partial :phases :reason :deadline-exceeded])
      (nil? (:status evidence)) (assoc :status "failed")
      (nil? (:phase evidence)) (assoc :phase (name fallback-phase)))
    {:status "failed"
     :phase (name fallback-phase)
     :message (str evidence)}))

(defn- interruption
  [signal]
  (when-let [name (process/signal-name signal)]
    {:signal name :cause (.-reason ^js signal)}))

(defn- interrupted?
  [state]
  (some? (interruption (:signal state))))

(defn- check-deadline
  [deadline]
  (when (zero? (process/remaining deadline))
    (throw (ex-info "case deadline exceeded" {:type ::browser/timeout :phase :deadline}))))

(defn- failure-outcome
  [state deadline evidence]
  (cond
    (interrupted? state) :interrupted
    (if (contains? evidence :deadline-exceeded)
      (:deadline-exceeded evidence)
      (or (zero? (process/remaining deadline)) (= "deadline" (:phase evidence)))) :timed-out
    :else :failed))

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
  (let [failure (cond-> (assoc (select-keys evidence [:status :phase :message :cause :partial :reason])
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
  [state case page deadline]
  (if (:screenshots (:opts state))
    (let [{:keys [file path]} (runs/screenshot-path (:home state) (:id case))
          bounds {:deadline deadline
                  :signal (:signal state) :phase :screenshot}
          shot   (attempt (await ((get-in state [:services :screenshot]) page path bounds)))]
      (if (failure? shot)
        (do
          (record-screenshot-status state (:id case)
                                    {:file file :status :failed
                                     :message (errors/message-of shot)
                                     :cause (errors/describe-cause shot)})
          (write-log state log/warn (str "Screenshot failed for "
                                         (result/case-name (:id case)) ": "
                                         (errors/message-of shot) "\n"))
          (let [outcome (failure-outcome state deadline (cause-evidence shot :screenshot))]
            (when (= :timed-out outcome)
              (record-failure state (:id case) :case-deadline (cause-evidence shot :screenshot)))
            (if (= :failed outcome) :ok outcome)))
        (do
          (record-screenshot-status state (:id case) {:file file :status :ok})
          (write-log state log/debug (str "Screenshot " file))
          :ok)))
    :ok))

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
  [state case page deadline warmup?]
  (check-deadline deadline)
  (let [build-id (:id (:selected-build state))
        preparation-id (str (random-uuid))
        response (attempt
                  (await ((get-in state [:services :prepare])
                          page
                          (transit/encode-str
                           {:case case
                            :preparation-id preparation-id
                            :timeout-ms (max 1 (process/remaining deadline))
                            :module-url (renderer-url build-id "render-wasm.js")
                            :wasm-url (renderer-url build-id "render-wasm.wasm")})
                          {:deadline deadline :signal (:signal state) :phase :preparation})))
        accepted (when (and (not (failure? response)) (= "ok" (:status response)))
                   (attempt
                    (let [preparation (assoc (:preparation response) :warmup? warmup?)]
                      (when-not (and (= preparation-id (:preparation-id preparation))
                                     (= (:id case) (:case-id preparation))
                                     (not (:failure preparation)))
                        (throw (ex-info "preparation success response is inconsistent" {})))
                      (record-preparation state preparation)
                      preparation)))]
    (if (and accepted (not (failure? accepted)))
      {:preparation accepted}
      (let [failure (cond
                      (failure? accepted) (assoc (cause-evidence accepted :invalid-output)
                                                 :partial {:response response})
                      (failure? response) (cause-evidence response :preparation)
                      :else (failure-evidence response :preparation))
            phases (if (failure? accepted) (get-in response [:preparation :phases]) (:phases failure))]
        (record-preparation state {:preparation-id preparation-id :case-id (:id case)
                                   :warmup? warmup?
                                   :phases (measurement/partial-phases phases preparation-id [:preparation])
                                   :diagnostics (or (get-in failure [:partial :setup]) {})
                                   :failure (dissoc failure :phases)})
        {:failure failure :preparation-id preparation-id}))))

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
    (let [closed (attempt (await ((get-in state [:services :close]) owner)))]
      (if (failure? closed)
        (do
          (record-failure state nil :browser-close (cause-evidence closed :browser-close))
          (reset! (:abort-run? state) true))
        (do
          (reset! (:owner-ref state) nil)
          (reset! (:page-ref state) nil))))))

(defn- ^:async close-current-page
  [state case]
  (let [owner      @(:owner-ref state)
        connected? (get-in state [:services :connected?])]
    (if (and owner connected? (not (connected? owner)))
      (do
        (record-failure state (:id case) :browser-disconnected
                        {:phase "browser-disconnected" :message "browser disconnected"})
        (reset! (:page-ref state) nil)
        (await (recycle-browser state))
        false)
      (if-let [page @(:page-ref state)]
        (do
          (reset! (:page-ref state) nil)
          (let [closed (attempt (await ((get-in state [:services :close-page]) page)))]
            (if (failure? closed)
              (do
                (record-failure state (:id case) :browser-close (cause-evidence closed :browser-close))
                (await (recycle-browser state))
                false)
              true)))
        true))))

(defn- ^:async ensure-browser
  [state deadline]
  (or @(:owner-ref state)
      (let [opts    (:opts state)
            options {:startup-timeout-ms (:startup-timeout-ms opts)
                     :signal (:signal state) :deadline deadline}
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
  [state case owner deadline]
  (let [opened (attempt
                (await ((get-in state [:services :new-page])
                        owner @(:host-ref state)
                        {:viewport (declarations/resolve-viewport (:view case))
                         :deadline deadline
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
      (let [evidence (cause-evidence opened :browser-page)
            outcome (failure-outcome state deadline evidence)]
        (record-failure state (:id case) :browser-page evidence)
        (await (recycle-browser state))
        {:outcome outcome}))))

(defn- ^:async open-page
  [state case deadline]
  (check-deadline deadline)
  (if (interrupted? state)
    {:outcome :interrupted}
    (let [owner (attempt (await (ensure-browser state deadline)))]
      (cond
        (not (failure? owner))
        (await (open-case-page state case owner deadline))

        (interrupted? state)
        {:outcome :interrupted}

        :else
        (let [evidence (cause-evidence owner :browser-launch)
              outcome (failure-outcome state deadline evidence)]
          (record-failure state (:id case) :browser-launch evidence)
          (if (and (= :timed-out outcome)
                   (not= ::browser/startup-cleanup (:type (ex-data owner))))
            {:outcome outcome}
            (do
              (reset! (:abort-run? state) true)
              {:outcome :abort})))))))

(defn- ^:async execute-slot
  [state case page preparation-id warmup? deadline]
  (let [response (await (run-attempt state page preparation-id deadline))
        evidence (if (failure? response) (cause-evidence response :attempt) response)
        accepted (attempt
                  (let [measured (measurement/attempt (:id case) preparation-id warmup? evidence)]
                    (record-attempt state measured)
                    measured))
        measured (if (failure? accepted)
                   (let [invalid (assoc (cause-evidence accepted :invalid-output)
                                        :phases (measurement/partial-phases (:phases evidence) preparation-id [:main :reset])
                                        :partial {:response evidence})
                         failed (measurement/attempt (:id case) preparation-id warmup? invalid)]
                     (record-attempt state failed)
                     failed)
                   accepted)]
    (cond
      (and (failure? response) (interrupted? state)) :interrupted
      (= :completed (:outcome measured)) :ok
      :else (failure-outcome state deadline (if (failure? response)
                                              (cause-evidence response :attempt)
                                              response)))))

(defn- ^:async finish-slot
  [state case page outcome final? deadline]
  (if (and final? (= :ok outcome))
    (await (screenshot-case state case page deadline))
    (do
      (when (not= :ok outcome)
        (record-screenshot-skip state (:id case) :no-completed-measured-attempt))
      outcome)))

(defn- ^:async run-fresh-slot
  [state case page warmup? final? deadline]
  (let [prepared (await (prepare-case state case page deadline warmup?))]
    (if-let [evidence (:failure prepared)]
      (do
        (record-attempt state (measurement/attempt (:id case) (:preparation-id prepared) warmup?
                                                   (dissoc evidence :phases)))
        (await (finish-slot state case page
                            (failure-outcome state deadline evidence) final? deadline)))
      (let [preparation (:preparation prepared)]
        (if (= :abort (check-gpu state preparation))
          :abort
          (let [outcome (await (execute-slot state case page (:preparation-id preparation)
                                             warmup? deadline))]
            (await (finish-slot state case page outcome final? deadline))))))))

(defn- ^:async run-fresh-page
  [state case warmup? final? deadline]
  (let [{:keys [page outcome]} (await (open-page state case deadline))]
    (if page
      (let [outcome (await (run-fresh-slot state case page warmup? final? deadline))]
        (if (= :ok outcome)
          (if (await (close-current-page state case)) outcome :failed)
          outcome))
      (or outcome :abandon))))

(defn- ^:async run-fresh-slots
  [state case slots deadline]
  (if-let [[warmup? & pending] (seq slots)]
    (let [outcome (await (run-fresh-page state case warmup? (empty? pending) deadline))]
      (if (= :ok outcome)
        (run-fresh-slots state case pending deadline)
        outcome))
    :done))

(defn- ^:async run-fresh-case
  [state case deadline]
  (let [outcome (await (run-fresh-slots state case (case-slots (:opts state)) deadline))]
    (ensure-screenshot-status state case :case-abandoned)
    outcome))

(defn- ^:async run-reuse-slots
  [state case page preparation-id slots deadline]
  (if-let [[warmup? & pending] (seq slots)]
    (let [_ (check-deadline deadline)
          executed (await (execute-slot state case page preparation-id warmup? deadline))
          outcome (await (finish-slot state case page executed (empty? pending) deadline))]
      (if (= :ok outcome)
        (run-reuse-slots state case page preparation-id pending deadline)
        outcome))
    :done))

(defn- ^:async abandon-preparation
  [state case evidence deadline]
  (record-failure state (:id case) :preparation evidence)
  (ensure-screenshot-status state case :no-completed-measured-attempt)
  (failure-outcome state deadline evidence))

(defn- ^:async run-reuse-prepared
  [state case page preparation deadline]
  (let [accepted (check-gpu state preparation)
        outcome  (if (= :abort accepted)
                   :abort
                   (await (run-reuse-slots state case page (:preparation-id preparation)
                                           (case-slots (:opts state)) deadline)))]
    (ensure-screenshot-status state case :case-abandoned)
    (if (= :done outcome)
      (if (await (close-current-page state case)) outcome :failed)
      outcome)))

(defn- ^:async run-reuse-page
  [state case page deadline]
  (let [prepared (await (prepare-case state case page deadline false))]
    (if-let [evidence (:failure prepared)]
      (await (abandon-preparation state case evidence deadline))
      (await (run-reuse-prepared state case page (:preparation prepared) deadline)))))

(defn- ^:async run-reuse-case
  [state case deadline]
  (let [{:keys [page outcome]} (await (open-page state case deadline))]
    (cond
      page (await (run-reuse-page state case page deadline))
      (= :abort outcome) :abort

      :else
      (do
        (ensure-screenshot-status state case :case-abandoned)
        (or outcome :abandon)))))


(defn- fail-startup
  "Records the startup failure and aborts the run. An interrupted run records
  nothing. Returns false."
  [state stage cause]
  (when-not (interrupted? state)
    (record-failure state nil stage (cause-evidence cause stage))
    (reset! (:abort-run? state) true))
  false)

(defn- ^:async launch-initial
  [state deadline]
  (let [owner (attempt (await (ensure-browser state deadline)))]
    (if (failure? owner)
      (fail-startup state :browser-launch owner)
      true)))

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

(defn- get-case-failure
  [run case-id]
  (or (some :failure (reverse (filter #(= case-id (:case-id %)) (:attempts run))))
      (some :failure (reverse (filter #(= case-id (:case-id %)) (:preparations run))))
      (some #(when (= case-id (:case-id %)) %)
            (reverse (get-in run [:metadata :diagnostics :failures])))))

(defn- ^:async run-case
  [state case deadline]
  (let [executed (attempt
                  (if (= :fresh (:context case))
                    (await (run-fresh-case state case deadline))
                    (await (run-reuse-case state case deadline))))
        outcome (if (failure? executed)
                  (let [evidence (cause-evidence executed :case)]
                    (record-failure state (:id case) :case evidence)
                    (failure-outcome state deadline evidence))
                  executed)
        unmeasured? (and (= :done outcome)
                         (not (result/has-measurements? @(:run-ref state) (:id case))))
        outcome (if unmeasured?
                  (do
                    (record-failure state (:id case) :case
                                    {:phase "case" :reason :no-measurements
                                     :message "case has no measurements"})
                    :failed)
                  outcome)
        saved-outcome (cond
                        (= :done outcome) :completed
                        (= :interrupted outcome) :interrupted
                        (= :timed-out outcome) :timed-out
                        :else :failed)
        failure (when (not= :completed saved-outcome)
                  (get-case-failure @(:run-ref state) (:id case)))]
    (swap! (:run-ref state) result/record-case-outcome (:id case)
           (cond-> {:outcome saved-outcome}
             failure (assoc :failure failure)))
    (ensure-screenshot-status state case :case-abandoned)
    (persist state)
    (when (not= :done outcome)
      (await (recycle-browser state)))
    outcome))

(defn- ^:async run-cases
  [state cases]
  (when (and (seq cases) (session-active? state))
    (let [case (first cases)
          deadline (process/deadline (:timeout-ms (:opts state)))]
      (write-log state log/info (str "▸ Case " (result/case-name (:id case))
                                     " (" (name (:context case)) ")"))
      (let [outcome (await (run-case state case deadline))]
        (if (#{:abort :interrupted} outcome)
          (do
            (reset! (:abort-run? state) true)
            outcome)
          (run-cases state (rest cases)))))))

(defn- ^:async run-session
  [state]
  (let [deadline (process/deadline (:startup-timeout-ms (:opts state)))
        provenance-ready? (await (record-provenance state deadline))
        server-ready? (and provenance-ready? (await (start-server state deadline)))
        browser-ready? (and server-ready? (session-active? state) (await (launch-initial state deadline)))]
    (when (and browser-ready? (session-active? state))
      (await (run-cases state (:cases state))))))

(defn- get-termination
  [run interrupted harness-failed?]
  (let [all-attempted? (every? (fn [[_ slots]]
                                 (and (zero? (:warmups slots))
                                      (zero? (:repetitions slots))))
                               (:remaining run))
        failed?       (or (some #(= :failed (:outcome %)) (:attempts run))
                          (some #(not= :completed (:outcome %)) (vals (:case-outcomes run))))
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
    (swap! (:run-ref state) result/finish-run (time/now) termination)
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
   :diagnostics (cond-> {:seed (:seed opts)
                         :filter (:filter opts)
                         :warmups (:warmups opts)
                         :repetitions (:repetitions opts)
                         :timeout-ms (:timeout-ms opts)
                         :startup-timeout-ms (:startup-timeout-ms opts)
                         :screenshots? (boolean (:screenshots opts))
                         :require-gpu? (boolean (:require-gpu opts))
                         :penpot-gpu (or (.-PENPOT_GPU (.-env js/process)) nil)
                         :runner-git {:sha nil :dirty nil}
                         :runtime-helper-identity :unverified
                         :screenshots {}
                         :failures []}
                  (:build-timeout-ms opts) (assoc :build-timeout-ms (:build-timeout-ms opts)))})

(defn- create-run-record
  [root run-id selected-build cases opts]
  (result/create-run
   {:run-id run-id
    :started-at (time/now)
    :plan {:cases cases
           :warmups (:warmups opts)
           :repetitions (:repetitions opts)
           :timeout-policy {:case-ms (:timeout-ms opts)}}
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
  "Executes a prepared selection. Owns host and browser.

  - `root`: the frontend checkout root.
  - `home`: the reserved output home.
  - `selected-build`: the prepared build.
  - `cases`: the selected cases.
  - `options`: resolved execution settings, including optional local :browser services.
  - `signal`: an optional abort signal.

  A browser startup failure leaves a valid partial record.

  Returns the run, home, report, log path, exit code and the failures in
  :errors, a cleanup failure first. :errors includes any failed record or log
  write, including a failure followed by a successful write. An interruption
  is not a failure."
  [root home selected-build cases options signal]
  (binding [log/*log-path* (:log-path home)]
    (let [state (create-run-state root home selected-build cases options signal)]
      (persist state)
      (let [session-error (await (execute-session state))
            cleanup-error (await (cleanup-session state))]
        (finish-execution state session-error cleanup-error)))))

(defn- ^:async get-selected-build
  [root opts signal]
  (let [{:keys [kind id default-features features build-timeout-ms]} (:build opts)]
    (case kind
      :existing (artifacts/load-build root id)
      :prepare (await (build/prepare
                       {:root root
                        :selection {:default-features? default-features :features features}
                        :build-timeout-ms build-timeout-ms :signal signal})))))

(defn- get-cases
  [selected-build opts]
  (try
    (catalog/collect selected-build {:master-seed (:seed opts) :filter (:filter opts)})
    (catch :default cause
      (throw (ex-info (errors/message-of cause)
                      (assoc (ex-data cause) :build-id (:id selected-build)
                             :log-path (:log-path selected-build))
                      cause)))))

(defn ^:async run
  "Prepares or loads a build, collects matching cases and executes one run.

  - `root`: the frontend checkout root.
  - `options`: resolved run options with a tagged :build source.
  - `signal`: an optional abort signal.
  - `services`: local service overrides, with browser functions under :browser.

  Returns an outcome with the reserved home, available evidence and failures."
  [root options signal services]
  (let [home (runs/create-home {:root root :run-id (str (random-uuid))
                                :output-dir (:output-dir options) :remove (:remove options)})
        opts (cond-> (assoc (get-execution-options options) :browser (:browser services))
               (= :prepare (get-in options [:build :kind]))
               (assoc :build-timeout-ms (get-in options [:build :build-timeout-ms])))]
    (binding [log/*log-path* (:log-path home)]
      (try
        (let [selected-build (await (get-selected-build root options signal))
              cases          (get-cases selected-build opts)]
          (log/info (str "  build " (:id selected-build)))
          (log/info (str "  selected " (count cases) " cases"))
          (await (execute root home selected-build cases opts signal)))
        (catch :default cause
          (outcome/failed (-> cause errors/as-failure
                              (errors/with-data {:log-path (:log-path home)}))
                          {:home home :run nil :report nil}))))))

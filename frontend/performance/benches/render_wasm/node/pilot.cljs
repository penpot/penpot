;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.pilot
  "Executes one unscored case using a prepared or retained renderer build.
  Stdout contains one raw RunRecord schema3 in Transit; build IDs, progress and
  reports go to stderr. The saved plan always has 0 warmups and 1 attempt.
  Node/browser summaries must agree outside measurement.

  Build and startup have separate bounds. The case deadline covers preparation
  and its one attempt; screenshots, reporting and disposal stay outside it.
  Exit code 0 means the diagnostic completed, not a performance conclusion.

  Example: node target/performance/render-wasm/pilot.cjs --build ID
           --case rects/pan --seed 42 --warmups 0 --attempts 1"
  (:require
   ["module" :as node-module]
   ["os" :as os]
   [app.common.transit :as transit]
   [benches.render-wasm.declarations :as declarations]
   [benches.render-wasm.failures :as failures]
   [benches.render-wasm.measurement :as measurement]
   [benches.render-wasm.node.artifacts :as artifacts]
   [benches.render-wasm.node.browser :as browser]
   [benches.render-wasm.node.build :as build]
   [benches.render-wasm.node.catalog :as catalog]
   [benches.render-wasm.node.process :as process]
   [benches.render-wasm.node.server :as server]
   [benches.render-wasm.random :as random]
   [benches.render-wasm.report.format :as format]
   [benches.render-wasm.report.summarize :as summary]
   [benches.render-wasm.result :as result]
   [clojure.string :as str]))

(def ^:private default-seed 42)
(def ^:private default-timeout-ms 30000)

(defn- usage
  []
  (str "pilot [--build ID] [--case SCENE/NAME] [--seed N]\n"
       "      [--timeout-ms N] [--build-timeout-ms N] [--startup-timeout-ms N]\n"
       "      [--no-default-features] [--features NAME,NAME]\n"
       "      [--warmups 0] [--attempts 1] [--screenshot PATH] [--help]\n"
       "Defaults: rects/load, seed 42, timeout 30000, build timeout 300000,\n"
       "startup timeout 30000, 0 warmups, 1 attempt.\n"
       "--build cannot be combined with feature-selection flags."))

(defn- invalid-args
  [message]
  (throw (ex-info message {:type ::invalid-args})))

(defn- parse-integer
  [flag value]
  (when-not (re-matches #"[0-9]+" value)
    (invalid-args (str flag " requires a nonnegative integer")))
  (let [number (js/Number value)]
    (when-not (js/Number.isSafeInteger number)
      (invalid-args (str flag " requires a safe integer")))
    number))

(defn- parse-timeout
  [flag value]
  (let [number (parse-integer flag value)]
    (when-not (<= 1 number 2147483647)
      (invalid-args (str flag " must be between 1 and 2147483647")))
    number))

(defn parse-args
  "Parses the diagnostic CLI without loading a catalog or starting resources.
  Case availability and Cargo features are checked by the selected build APIs."
  [argv]
  (loop [args argv
         seen #{}
         opts {:case-id :rects/load :seed default-seed
               :timeout-ms default-timeout-ms
               :build-timeout-ms process/default-build-timeout-ms
               :startup-timeout-ms process/default-startup-timeout-ms
               :warmups 0 :attempts 1
               :features {:default-features? true :features []}}]
    (if (empty? args)
      (do
        (when-not (:help? opts)
          (when (and (:build-id opts) (some seen ["--no-default-features" "--features"]))
            (invalid-args "--build cannot be combined with feature-selection flags"))
          (when-not (random/seed? (:seed opts))
            (invalid-args "--seed must be a uint32 integer"))
          (when-not (and (zero? (:warmups opts)) (= 1 (:attempts opts)))
            (invalid-args "the diagnostic pilot requires --warmups 0 and --attempts 1")))
        (cond-> (dissoc opts :features-text)
          (contains? opts :features-text)
          (assoc-in [:features :features] (build/parse-feature-list (:features-text opts)))))
      (let [[flag & more] args]
        (cond
          (#{"--help" "-h"} flag)
          (recur more (conj seen flag) (assoc opts :help? true))

          (= flag "--no-default-features")
          (recur more (conj seen flag) (assoc-in opts [:features :default-features?] false))

          (#{"--build" "--case" "--seed" "--timeout-ms" "--build-timeout-ms"
             "--startup-timeout-ms" "--warmups" "--attempts" "--features" "--screenshot"} flag)
          (let [[value & rest-args] more]
            (when (or (nil? value) (str/starts-with? value "--"))
              (invalid-args (str flag " requires a value")))
            (when (and (#{"--build" "--screenshot"} flag) (str/blank? value))
              (invalid-args (str flag " requires a nonempty value")))
            (when (and (= flag "--case") (not (re-matches #"[^/\s]+/[^/\s]+" value)))
              (invalid-args "--case requires SCENE/NAME"))
            (recur rest-args (conj seen flag)
                   (case flag
                     "--build" (assoc opts :build-id value)
                     "--case" (assoc opts :case-id (keyword value))
                     "--seed" (assoc opts :seed (parse-integer flag value))
                     "--timeout-ms" (assoc opts :timeout-ms (parse-timeout flag value))
                     "--build-timeout-ms" (assoc opts :build-timeout-ms (parse-timeout flag value))
                     "--startup-timeout-ms" (assoc opts :startup-timeout-ms (parse-timeout flag value))
                     "--warmups" (assoc opts :warmups (parse-integer flag value))
                     "--attempts" (assoc opts :attempts (parse-integer flag value))
                     "--features" (assoc opts :features-text value)
                     "--screenshot" (assoc opts :screenshot value))))

          :else
          (invalid-args (str "unknown pilot option: " flag)))))))

(defn- trace
  [message]
  (.error js/console message))

(defn- pilot-metadata
  "Uses the selected manifest as primary provenance, including on reuse."
  [selected-build opts]
  (let [manifest        (:manifest selected-build)
        cpus            (array-seq (os/cpus))
        require-package (if (exists? js/require)
                          js/require
                          (node-module/createRequire (:cases-path selected-build)))
        ^js package     (require-package "playwright/package.json")]
    {:git (merge {:sha nil :dirty nil} (get-in manifest [:provenance :git]))
     :build {:functional (:functional manifest)
             :provenance (assoc (:provenance manifest) :build-id (:id selected-build))}
     :environment {:node (.-version js/process) :playwright (.-version package)
                   :browser nil :platform (os/platform) :release (os/release) :arch (os/arch)
                   :cpu {:models (vec (distinct (map #(.-model %) cpus))) :logical-count (count cpus)}
                   :launch browser/launch-settings}
     :scored? false
     :diagnostics (assoc (select-keys opts [:seed :screenshot])
                         :runner-git {:sha nil :dirty nil}
                         :runtime-helper-identity :unverified)}))

(defn- complete-record
  "Retains evidence and fixed slots. A fresh preparation consumes its attempt;
  a failed warm preparation leaves the attempt unstarted."
  [run preparation evidence attempted?]
  (let [id  (get-in run [:plan :cases 0 :id])
        run (cond-> run preparation (result/record-preparation preparation))
        run (if attempted?
              (result/record-attempt run (measurement/attempt id (:preparation-id preparation) false evidence))
              run)
        ok? (= "ok" (:status evidence))]
    (result/finish-run run (.toISOString (js/Date.))
                       (cond-> {:reason (if ok? :completed (if attempted? :failed :preparation-failed))}
                         (not ok?) (assoc :failure (select-keys evidence [:status :phase :message :cause])
                                          :partial (:partial evidence))))))

(defn- failed-record
  [run preparation attempted? phase cause]
  (let [evidence {:status "failed" :phase (name phase)
                  :message (or (ex-message cause) (str cause))
                  :cause (failures/describe-cause cause)}]
    (if (= :running (get-in run [:termination :reason]))
      (complete-record run preparation evidence attempted?)
      (assoc run :termination {:reason :diagnostic-failed :failure evidence
                               :prior-termination (:termination run)}))))

(defn- ^:async execute-case
  [session selected selected-build opts signal preparation-ref attempted-ref]
  (let [page     (:page session)
        deadline (process/deadline (:timeout-ms opts))
        bounds   {:deadline deadline :signal signal :phase :pilot-case}
        stamp    (str "?build=" (js/encodeURIComponent (:id selected-build)))
        request  (transit/encode-str {:case selected :timeout-ms (:timeout-ms opts)
                                      :module-url (str "/renderer/render-wasm.js" stamp)
                                      :wasm-url (str "/renderer/render-wasm.wasm" stamp)})
        prepared (transit/decode-str
                  (await (browser/within
                          (fn []
                            (when (= :fresh (:context selected))
                              (reset! attempted-ref true))
                            (browser/evaluate page "prepareScene" request))
                          bounds)))]
    (if (= "ok" (:status prepared))
      (do
        (reset! preparation-ref (:preparation prepared))
        (transit/decode-str
         (await (browser/within
                 (fn []
                   (reset! attempted-ref true)
                   (browser/evaluate page "runPrepared"
                                     (transit/encode-str
                                      {:preparation-id (get-in prepared [:preparation :preparation-id])
                                       :timeout-ms (process/remaining deadline)})))
                 bounds))))
      prepared)))

(defn- ^:async check-diagnostics
  [session run opts signal]
  (let [^js page (:page session)
        deadline (process/deadline (:startup-timeout-ms opts))
        bounds   {:deadline deadline :signal signal :phase :pilot-diagnostics}
        report   (summary/summarize run)]
    (when-let [shot (:screenshot opts)]
      (await (browser/within #(.screenshot page #js {:path shot :timeout (process/remaining deadline)})
                             bounds)))
    (let [browser-report (transit/decode-str
                          (await (browser/within #(browser/evaluate page "summarize" (result/encode run))
                                                 bounds)))]
      (when-not (= report browser-report)
        (throw (ex-info "Node/browser summaries differ" {:type ::summary-mismatch})))
      (trace "pilot: Node/browser summaries agree"))
    (await (browser/within #(browser/evaluate page "dispose") bounds))))

(defn- ^:async run-pilot
  [opts]
  (let [root            (artifacts/frontend-root)
        controller      (js/AbortController.)
        signal          (.-signal controller)
        interrupted-ref (atom nil)
        remove-signals  (process/on-signals
                         (fn [name]
                           (when-not (.-aborted signal)
                             (reset! interrupted-ref name)
                             (.abort controller (ex-info "pilot interrupted" {:type ::interrupted :signal name})))))
        host-ref        (atom nil)
        session-ref     (atom nil)
        preparation-ref (atom nil)
        attempted-ref   (atom false)
        run-ref         (atom nil)
        phase-ref       (atom :pilot-build)
        started-at      (.toISOString (js/Date.))]
    (letfn [(failed [cause phase]
              (trace (str "pilot: " (or (ex-message cause) (str cause))))
              (when-some [data (ex-data cause)]
                (trace (pr-str data)))
              (when @run-ref
                (swap! run-ref failed-record @preparation-ref @attempted-ref phase cause)))]
      (try
        (let [selected-build (if-let [id (:build-id opts)]
                               (do
                                 (trace (str "pilot: using build " id))
                                 (artifacts/load-build root id))
                               (await (build/prepare
                                       {:root root :features (:features opts)
                                        :build-timeout-ms (:build-timeout-ms opts) :signal signal
                                        :on-created #(trace (str "pilot: build " (:id %)))})))
              _              (reset! phase-ref :pilot-catalog)
              descriptors    (catalog/collect selected-build
                                              {:master-seed (:seed opts)
                                               :filter (result/case-name (:case-id opts))})
              selected       (or (some #(when (= (:case-id opts) (:id %)) %) descriptors)
                                 (throw (ex-info "case is absent from the selected build"
                                                 {:build-id (:id selected-build) :case-id (:case-id opts)})))
              startup-end    (process/deadline (:startup-timeout-ms opts))]
          (reset! run-ref
                  (result/create-run
                   {:run-id (str "pilot-" started-at) :started-at started-at
                    :plan {:cases [selected] :warmups 0 :repetitions 1
                           :timeout-policy {:attempt-ms (:timeout-ms opts) :pilot-case-ms (:timeout-ms opts)
                                            :build-ms (:build-timeout-ms opts) :startup-ms (:startup-timeout-ms opts)}}
                    :metadata (pilot-metadata selected-build opts)}))
          (reset! phase-ref :pilot-provenance)
          (swap! run-ref assoc-in [:metadata :diagnostics :runner-git]
                 (await (build/git-provenance root {:deadline startup-end :signal signal})))
          (reset! phase-ref :pilot-server-startup)
          (reset! host-ref (await (server/open selected-build
                                               {:startup-timeout-ms (process/remaining startup-end)
                                                :signal signal})))
          (reset! phase-ref :pilot-browser-startup)
          (reset! session-ref (await (browser/open @host-ref
                                                   {:viewport (declarations/resolve-viewport (:view selected))
                                                    :startup-timeout-ms (:startup-timeout-ms opts)
                                                    :deadline startup-end :signal signal})))
          (swap! run-ref assoc-in [:metadata :environment :browser] (:version @session-ref))
          (trace "pilot: retained browser identity verified")
          (reset! phase-ref :pilot-case)
          (let [evidence (await (execute-case @session-ref selected selected-build opts signal
                                              preparation-ref attempted-ref))]
            (swap! run-ref complete-record @preparation-ref evidence @attempted-ref))
          (reset! phase-ref :pilot-diagnostics)
          (await (check-diagnostics @session-ref @run-ref opts signal)))
        (catch :default cause
          (failed cause @phase-ref))
        (finally
          (try
            (when @session-ref
              (try
                (await (browser/close @session-ref))
                (catch :default cause
                  (failed cause :pilot-browser-cleanup))))
            (when @host-ref
              (try
                (await (server/close @host-ref))
                (catch :default cause
                  (failed cause :pilot-server-cleanup))))
            (finally
              (remove-signals)))))
      (when (and @run-ref @interrupted-ref)
        (swap! run-ref update :termination assoc :reason :interrupted :signal @interrupted-ref))
      (when @run-ref
        (try
          (trace (format/format-run (summary/summarize @run-ref)))
          (catch :default cause
            (failed cause :pilot-report))))
      @run-ref)))

(defn ^:async -main
  "Runs the diagnostic and lets Node drain output after owned shutdown."
  [& argv]
  (try
    (let [opts (parse-args (or argv []))]
      (if (:help? opts)
        (do
          (println (usage))
          (set! (.-exitCode js/process) 0))
        (let [run (await (run-pilot opts))]
          (when run
            (println (result/encode run)))
          (set! (.-exitCode js/process) (if (= :completed (get-in run [:termination :reason])) 0 1)))))
    (catch :default cause
      (trace (str "pilot: " (or (ex-message cause) (str cause))))
      (when (= ::invalid-args (:type (ex-data cause)))
        (trace (usage)))
      (set! (.-exitCode js/process) 1))))

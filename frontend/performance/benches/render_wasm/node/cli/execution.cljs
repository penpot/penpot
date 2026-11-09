;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.cli.execution
  "Validate benchmark command requests, run their handlers and print results.

  Both the CLI entry and the separately compiled renderer command entry use
  this lifecycle. Callers supply a complete request and the handlers available
  at that entry. Handlers own the command's files, processes and cleanup; this
  module supplies cancellation, prints final reports and errors, and returns
  a numeric status for the entry to use as its exit code.

  `fail` reports parsing, loading or decoding failures that occur before a
  request reaches `execute`. Signal helpers also support work outside the
  checked command lifecycle. Execution and failure reporting share console
  settings; do not overlap calls that require different settings or streams."
  (:require
   [app.common.schema :as sm]
   [benches.render-wasm.errors :as errors :refer [attempt failure?]]
   [benches.render-wasm.node.cli.registry :as registry]
   [benches.render-wasm.node.cli.request :as request]
   [benches.render-wasm.node.log :as log]
   [benches.render-wasm.node.outcome :as outcome]))

(defn on-signals
  "Listen for process interrupts so the caller can stop work and finish cleanup.

  Args:
    callback: Function called with the string `SIGINT` or `SIGTERM` each time the
      Node process receives that signal while the listeners are installed.

  Returns:
    An idempotent function that removes only the listeners installed by this
    call. Call it when the work settles. This helper does not abort work,
    wait for cleanup or choose an exit status."
  [callback]
  (let [interrupt (fn [] (callback "SIGINT"))
        terminate (fn [] (callback "SIGTERM"))]
    (.on js/process "SIGINT" interrupt)
    (.on js/process "SIGTERM" terminate)
    (fn []
      (.removeListener js/process "SIGINT" interrupt)
      (.removeListener js/process "SIGTERM" terminate))))

(defn ^:async with-interruption
  "Run work with cooperative cancellation and preserve its result on interruption.

  The first SIGINT or SIGTERM aborts the supplied signal. Its reason is an
  exception with `:signal` set to that signal's name. Later signals do not
  replace the reason or force termination. The work must observe cancellation
  and settle after its own cleanup; this wrapper imposes no timeout and cannot
  preempt synchronous code. Listeners are removed after the work settles.

  Args:
    f: Function called once with an AbortSignal. Return an outcome map, or a
      promise of one, containing `:exit-code`, an `:errors` vector and
      `:log-path` (nil when there is no log). Additional fields may retain partial
      results, file paths or a final `:report` string.

  Returns:
    A promise of the outcome. Thrown or rejected failures become outcomes with
    exit code 1. After interruption, set the exit code to 130 for SIGINT or 143
    for SIGTERM and retain only errors recognized by `errors/fatal?`. Other
    outcome fields are preserved. This function does not print the outcome."
  [f]
  (let [controller     (js/AbortController.)
        signal         (.-signal controller)
        interrupted    (atom nil)
        remove-signals (on-signals
                        (fn [signal-name]
                          (when-not (.-aborted signal)
                            (reset! interrupted signal-name)
                            (.abort controller
                                    (ex-info "interrupted"
                                             {:type ::interrupted :signal signal-name})))))]
    (try
      (let [result   (attempt :catch-fatal (await (f signal)))
            finished (if (failure? result) (outcome/failed result {}) result)]
        (cond-> finished
          @interrupted (-> (update :errors #(filterv errors/fatal? %))
                           (assoc :exit-code (outcome/interruption-code @interrupted)))))
      (finally
        (remove-signals)))))

(defn- configure-console
  [console local]
  (log/configure (merge (select-keys local [:stdout :stderr]) console)))

(defn- check-gpu
  [command options]
  (when (and (:require-gpu options)
             (or (= command "run")
                 (and (= command "ab") (not (:dry-run options))))
             (not= "1" (.-PENPOT_GPU (.-env js/process))))
    (throw (ex-info "--require-gpu needs PENPOT_GPU=1. Start the container with ./manage.sh run-devenv --gpu and use pnpm run render-bench."
                    {:type ::gpu-required}))))

(defn- log-failures
  [finished]
  (doseq [[index cause] (map-indexed vector (:errors finished))]
    (let [data       (ex-data cause)
          context    (mapv (fn [[key value]]
                             [key (if (string? value)
                                    (errors/bounded-text (js/JSON.stringify value) 1024)
                                    value)])
                           (errors/project-context data))
          path       (when-let [path (:path data)]
                       (errors/bounded-text (if (string? path)
                                              (js/JSON.stringify path)
                                              (pr-str path))
                                            1024))
          validation (some-> (::sm/explain data) sm/simplify)]
      (log/error {:message (errors/message-of cause)
                  :fields (into (filterv (comp some? second)
                                         [[:type (:type data)] [:cause-type (:cause-type data)]
                                          [:path path]
                                          [:role (:role data)] [:operation (:operation data)]
                                          [:phase (:phase data)] [:build (:build-id data)]
                                          [:reason (:reason data)] [:manifest-error (:manifest-error data)]
                                          [:baseline (:baseline data)] [:candidate (:candidate data)]
                                          [:features (:features data)]
                                          [:count (:count data)] [:max-features (:max-features data)]
                                          [:validation validation]])
                                (concat context
                                        (map #(vector :difference (:field %)) (:mismatches data))))
                  :tail-log (:log-path data)
                  :tail? (zero? index)}))))

(defn- report-fallback
  [cause local]
  (attempt :catch-fatal
           (let [message (errors/message-of cause)
                 stream  (or (:stderr local) (.-stderr js/process))]
             (.write ^js stream
                     (str "Could not report benchmark failure: "
                          (subs message 0 (min 1024 (count message))) "\n")))))

(defn- finish
  "Print the optional report and errors without retrying failed report writes."
  [result local]
  (binding [log/*log-path* (:log-path result)]
    (let [finished (if-some [report (:report result)]
                     (outcome/publish result #(log/result report))
                     result)
          reported (attempt :catch-fatal (log-failures finished))]
      (if (failure? reported)
        (do
          (report-fallback reported local)
          (:exit-code (outcome/add-error finished reported)))
        (:exit-code finished)))))

(defn fail
  "Report a failure that occurred before checked command execution could begin.

  Use this for argument parsing, module loading or request decoding failures.
  Do not report a failure again after `execute` has returned its numeric status.

  Args:
    cause: Error or thrown value to report. Error data may supply a log path,
      usage text, validation details or comparison differences for diagnosis.
    console: Trusted console settings with `:level` (`:info`, `:warn`, `:debug`)
      and `:color` (`:auto`, `:always`, `:never`). An empty map uses defaults.
      Do not take these settings from a request that failed validation.
    local: Optional `:stdout` and `:stderr` writable streams with a Node-style
      `write` method. Omitted streams use the process streams.

  Returns:
    The number 1 after attempting to write the error to stderr and any log
    named by its error data. Reporting failures get one bounded stderr fallback
    attempt. The one-argument form uses default console settings and streams."
  ([cause]
   (fail cause {} {}))
  ([cause console local]
   (configure-console console local)
   (finish (outcome/failed (errors/as-failure cause) {}) local)))

(defn ^:async execute
  "Validate a command request, await its handler and print its final outcome.

  Check the request without adding defaults or coercing values. Invalid
  requests use default console settings; valid requests choose their console
  settings before handler lookup and command policy checks. Unsupported
  commands and policy failures are reported without invoking a handler or
  looking up the frontend root.

  For `run` and non-dry-run `ab` commands, `:require-gpu` requires the environment
  variable `PENPOT_GPU` to equal the string `1` before invoking the handler.
  This checks an environment prerequisite, not actual GPU availability.

  Args:
    received: Complete request map with a string `:command`, resolved
      `:console` settings and command-specific `:options`. Obtain it from
      `cli/parse-resolve` or decode it from the CLI's Transit request. Missing
      required fields and unknown fields fail validation.
    handlers: Map of supported command-name strings to functions called as
      `(handler context options)`. Return an outcome or a promise of one, with
      `:exit-code`, an `:errors` vector and `:log-path` (nil when there is no log).
      An optional `:report` string supplies all final stdout text. Other
      result fields do not generate output. Handlers own progress logging
      and must await their work and cleanup before returning.
    local: Optional map containing `:stdout` and `:stderr` writable streams,
      a zero-argument `:get-root` function returning the frontend directory,
      and a `:services` map of handler-specific overrides. The context contains
      an AbortSignal at `:signal`, plus `:get-root` and `:services` when supplied.
      Root lookup and service calls remain the handler's responsibility. These
      functions, streams and services are local, not part of the request.

  Returns:
    A promise of a numeric exit status after the handler settles and final
    output writes have been attempted. Print `:report` once, when present,
    followed by errors on stderr, using the outcome's log path for file output.
    No report means no final stdout text. Output writes do not wait for streams
    to drain. The two-argument form uses process streams and supplies no root
    function or services.

    Validation, policy and handler failures produce status 1. Interruption
    follows `with-interruption`: await cleanup, preserve partial result fields
    and fatal errors, and return 130 for SIGINT or 143 for SIGTERM. A report or
    error-reporting failure changes other statuses to 1, with one bounded
    stderr fallback if error reporting fails. Interrupted statuses take
    precedence. Failed report writes are not retried."
  ([received handlers]
   (execute received handlers {}))
  ([received handlers local]
   (configure-console {} local)
   (let [result (attempt :catch-fatal
                         (let [{:keys [command console options]} (request/check received)]
                           (configure-console console local)
                           (let [handler (or (get handlers command)
                                             (throw (ex-info "command is unavailable at this execution entry"
                                                             {:type ::unsupported-command :command command})))
                                 options (registry/check-options command options)]
                             (check-gpu command options)
                             (await (with-interruption
                                      (fn [signal]
                                        (handler (assoc (select-keys local [:get-root :services])
                                                        :signal signal)
                                                 options)))))))
         result (if (failure? result) (outcome/failed result {}) result)]
     (finish result local))))

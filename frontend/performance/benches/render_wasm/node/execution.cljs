;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns benches.render-wasm.node.execution
  "Checked command execution, final publication and exit status."
  (:require
   [app.common.schema :as sm]
   [benches.render-wasm.errors :as errors :refer [attempt failure?]]
   [benches.render-wasm.node.cli.registry :as registry]
   [benches.render-wasm.node.log :as log]
   [benches.render-wasm.node.outcome :as outcome]
   [benches.render-wasm.node.request :as request]))

(defn on-signals
  "Installs SIGINT/SIGTERM callbacks receiving the signal name. Returns an
  idempotent remover. The caller can abort its work and let output drain."
  [callback]
  (let [interrupt (fn [] (callback "SIGINT"))
        terminate (fn [] (callback "SIGTERM"))]
    (.on js/process "SIGINT" interrupt)
    (.on js/process "SIGTERM" terminate)
    (fn []
      (.removeListener js/process "SIGINT" interrupt)
      (.removeListener js/process "SIGTERM" terminate))))

(defn ^:async with-interruption
  "Awaits `(f signal)` and returns its outcome. SIGINT/SIGTERM abort the signal
  and select status 130/143. Interrupted outcomes retain fatal errors and partial
  evidence. Thrown or rejected failures become outcomes."
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
          validation (some-> (::sm/explain data) sm/simplify)]
      (log/error {:message (errors/message-of cause)
                  :fields (into (filterv (comp some? second)
                                         [[:type (:type data)] [:cause-type (:cause-type data)]
                                          [:command (:command data)]
                                          [:phase (:phase data)] [:build (:build-id data)]
                                          [:reason (:reason data)] [:manifest-error (:manifest-error data)]
                                          [:baseline (:baseline data)] [:candidate (:candidate data)]
                                          [:features (:features data)]
                                          [:count (:count data)] [:max-features (:max-features data)]
                                          [:validation validation]])
                                (map #(vector :difference (:field %)) (:mismatches data)))
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
  "Prints a failure and returns its numeric status. Optional resolved console
  settings and local streams configure its output."
  ([cause]
   (fail cause {} {}))
  ([cause console local]
   (configure-console console local)
   (finish (outcome/failed (errors/as-failure cause) {}) local)))

(defn ^:async execute
  "Checks a complete received request, awaits its supported handler and returns
  a numeric exit status after final publication. Handlers receive local signal,
  root access and services plus resolved options. Local settings may supply
  streams, a root thunk and services."
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

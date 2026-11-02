;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns benches.render-wasm.node.cli
  "Command-specific argument resolution and help for renderer benchmarks."
  (:require
   [benches.render-wasm.feature-plan :as feature-plan]
   [benches.render-wasm.node.cli.console :as console]
   [benches.render-wasm.node.cli.help :as help]
   [benches.render-wasm.node.cli.registry :as registry]
   [benches.render-wasm.node.cli.shared :as shared]
   [benches.render-wasm.node.request :as request]
   [clojure.string :as str]
   [clojure.tools.cli :as tools]))

(def ^:private commands
  (into {} registry/commands))

(defn- get-definition
  [topic]
  (if (nil? topic)
    {:usage help/usage :description help/description :options []}
    (get commands topic)))

(defn- get-declarations
  [definition]
  (into help/cli-options (concat console/cli-options (:options definition))))

(defn- argument-error
  [topic message cause]
  (let [usage (or (:usage (get-definition topic)) help/usage)]
    (ex-info (str message "\nUsage: " usage)
             (assoc (ex-data cause)
                    :type ::invalid-args :cause-type (:type (ex-data cause))
                    :command topic :usage usage)
             cause)))

(defn parse-resolve
  "Returns a complete request for command-first argv, or throws an argument error.
  A leading pnpm forwarding delimiter is accepted."
  [argv]
  (let [argv      (if (= "--" (first argv)) (rest argv) argv)
        first-arg (first argv)
        topic     (when (and first-arg (not (str/starts-with? first-arg "-"))) first-arg)
        args      (if topic (rest argv) argv)]
    (when (and topic (not (contains? commands topic)))
      (throw (argument-error topic (str "unknown command: " topic) nil)))
    (let [definition (get-definition topic)]
      (try
        (let [{:keys [options arguments errors]}
              (tools/parse-opts args (get-declarations definition) :no-defaults true)
              _        (when (seq errors)
                         (throw (ex-info (str/join "\n" errors) {:type ::shared/invalid-args})))
              console  (console/resolve-options options)
              partial  (if (:help options)
                         (do
                           (when (and (nil? topic) (seq arguments))
                             (throw (ex-info "a command must precede its options"
                                             {:type ::shared/invalid-args})))
                           {:command "help" :console console :options {:topic topic}})
                         (do
                           (when-not topic
                             (throw (ex-info "a command is required" {:type ::shared/invalid-args})))
                           {:command topic :console console
                            :options ((:resolve definition)
                                      (dissoc options :help :quiet :verbose :color) arguments)}))
              resolved (request/check (request/apply-defaults partial))]
          (if (and (not= "help" (:command resolved)) (:check definition))
            (update resolved :options (:check definition))
            resolved))
        (catch :default cause
          (if (contains? #{::shared/invalid-args ::request/invalid-request
                           ::feature-plan/too-many-features ::feature-plan/confirmation-required}
                         (:type (ex-data cause)))
            (throw (argument-error topic (ex-message cause) cause))
            (throw cause)))))))

(defn- get-defaults
  [topic]
  (let [definition (get-definition topic)
        partial    {:command (or topic "help") :options (or (:help-options definition) {})}
        resolved   (request/apply-defaults partial)
        options    ((or (:help-defaults definition) identity) (:options resolved))]
    (merge (:console resolved) options)))

(defn get-help
  "Returns root help for nil or help for a known command name."
  [topic]
  (let [definition (get-definition topic)
        defaults   (get-defaults topic)
        summary    (:summary
                    (tools/parse-opts
                     [] (get-declarations definition)
                     :no-defaults true
                     :summary-fn
                     (fn [specs]
                       (tools/summarize
                        (mapv (fn [{:keys [id] :as spec}]
                                (if (contains? defaults id)
                                  (assoc spec :default (get defaults id)
                                         :default-desc (pr-str (get defaults id)))
                                  spec))
                              specs)))))]
    (str "Usage: " (:usage definition) "\n\n" (:description definition) "\n\n"
         (when (nil? topic)
           (str (str/join "\n" (map (fn [[_ definition]] (str "  " (:usage definition))) registry/commands))
                "\n\n"))
         "Options:\n" summary)))

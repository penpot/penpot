;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.runner
  "CLI parsing, offline comparison and deferred execution commands."
  (:require
   ["path" :as path]
   [app.common.transit :as transit]
   [benches.render-wasm.node.compare :as compare]
   [benches.render-wasm.node.options :as options]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]))

(enable-console-print!)

(def ^:private max-timeout-ms 2147483647)

(def ^:private positive-timeout
  [(fn [n] (and (js/Number.isSafeInteger n)
                (pos? n)
                (<= n max-timeout-ms)))
   "Must be a positive integer up to 2147483647"])

(def ^:private nonnegative-integer
  [(fn [n] (and (js/Number.isSafeInteger n) (not (neg? n))))
   "Must be a nonnegative integer"])

(def ^:private positive-integer
  [(fn [n] (and (js/Number.isSafeInteger n) (pos? n)))
   "Must be a positive integer"])

(def ^:private uint32
  [(fn [n] (and (js/Number.isSafeInteger n) (<= 0 n 4294967295)))
   "Must be a uint32 integer"])

(def ^:private nonempty
  [(fn [s] (not (str/blank? s))) "Must be a nonempty value"])

(defn- parse-number
  "Parses a numeric CLI value. Blank input stays invalid instead of becoming zero."
  [text]
  (if (str/blank? text) js/NaN (js/Number text)))

(def cli-options
  [["-h" "--help" "Print this help"]
   [nil "--[no-]default-features" "Enable Cargo defaults" :default true]
   [nil "--features FEATURES" "Comma-separated functional Cargo features"
    :parse-fn options/parse-feature-list]
   [nil "--build ID" "Reuse a ready build" :id :build-id
    :validate nonempty]
   [nil "--filter SUBSTR" "Select cases whose id contains SUBSTR"]
   [nil "--seed N" "Master scene seed" :default 42
    :parse-fn parse-number :validate uint32]
   [nil "--warmups N" "Warmup attempts per case" :default 3
    :parse-fn parse-number :validate nonnegative-integer]
   [nil "--repetitions N" "Measured attempts per case" :default 10
    :parse-fn parse-number :validate positive-integer]
   [nil "--timeout-ms MS" "Attempt deadline" :default 30000
    :parse-fn parse-number :validate positive-timeout]
   [nil "--build-timeout-ms MS" "Complete preparation deadline"
    :default options/default-build-timeout-ms
    :parse-fn parse-number :validate positive-timeout]
   [nil "--startup-timeout-ms MS" "Host/browser readiness deadline"
    :default options/default-startup-timeout-ms
    :parse-fn parse-number :validate positive-timeout]
   [nil "--output-dir DIR" "Explicit run home; must not exist"
    :validate nonempty]
   [nil "--remove" "Remove the used build after shutdown"]
   [nil "--screenshots" "Capture one screenshot per case"]
   [nil "--only-builds" "Purge build attempts"]
   [nil "--only-runs" "Purge managed runs"]
   [nil "--cases A,B" "Select one baseline,candidate pair"]
   [nil "--force" "Bypass comparison compatibility checks"]])

(defn usage
  [summary]
  (str "Usage: runner build [options]\n"
       "       runner purge [--only-builds | --only-runs]\n"
       "       runner run [options]\n"
       "       runner compare BASELINE CANDIDATE [--cases A,B] [--force]\n\n"
       "build prints one attempt ID after storage initialization. Exit 0 means\n"
       "ready; errors retain the build.transit and build.log for that ID.\n"
       "run prints one run ID on stdout. Progress, failures and the summary go\n"
       "to stderr. Builds and managed runs live under target/performance/render-wasm.\n"
       "compare reads saved RunRecords and starts no build, host or browser.\n\n"
       "Preparation and runs require an idle checkout with dependencies installed.\n"
       "Keep renderer watchers stopped until preparation ends. purge assumes no\n"
       "experiment is using the managed paths.\n\n"
       summary))

(def ^:private value-flags
  "Long options that consume the next argument when no value follows an equals sign."
  (into #{}
        (keep (fn [[_ long-option]]
                (when (and long-option (re-find #"\s" long-option))
                  (first (str/split long-option #"\s+")))))
        cli-options))

(defn- supplied-flags
  [args]
  (loop [tokens   (seq args)
         flags    #{}
         value?   false
         operand? false]
    (if-let [token (first tokens)]
      (cond
        value?
        (recur (next tokens) flags false operand?)

        (and (not operand?) (= "--" token))
        (recur (next tokens) flags false true)

        operand?
        (recur (next tokens) flags false true)

        (str/starts-with? token "--")
        (let [[flag inline-value] (str/split token #"=" 2)]
          (recur (next tokens)
                 (conj flags flag)
                 (and (contains? value-flags flag) (nil? inline-value))
                 false))

        :else
        (recur (next tokens) flags false false))
      flags)))

(def ^:private build-flags
  #{"--default-features" "--no-default-features" "--features" "--build-timeout-ms"})

(def ^:private run-flags
  #{"--build" "--filter" "--seed" "--warmups" "--repetitions" "--timeout-ms"
    "--startup-timeout-ms" "--output-dir" "--remove" "--screenshots"})

(def ^:private purge-flags
  #{"--only-builds" "--only-runs"})

(def ^:private compare-flags
  #{"--cases" "--force"})

(defn- invalid-args
  [message]
  (throw (ex-info message {:type ::invalid-args})))

(defn- some-flags?
  [flags group]
  (boolean (some flags group)))

(defn- check-command-flags!
  [command flags]
  (let [build?   (some-flags? flags build-flags)
        run?     (some-flags? flags run-flags)
        purge?   (some-flags? flags purge-flags)
        compare? (some-flags? flags compare-flags)]
    (case command
      "build"
      (do
        (when purge? (invalid-args "purge category flags require purge"))
        (when run? (invalid-args "run options require run"))
        (when compare? (invalid-args "compare options require compare")))

      "purge"
      (do
        (when build? (invalid-args "build options require build"))
        (when run? (invalid-args "run options require run"))
        (when compare? (invalid-args "compare options require compare")))

      "run"
      (do
        (when purge? (invalid-args "purge category flags require purge"))
        (when compare? (invalid-args "compare options require compare")))

      "compare"
      (do
        (when purge? (invalid-args "purge category flags require purge"))
        (when build? (invalid-args "build options require build"))
        (when run? (invalid-args "run options require run"))))))

(defn- check-arguments!
  [command arguments]
  (let [extra (rest arguments)]
    (case command
      "compare"
      (when-not (= 2 (count extra))
        (invalid-args "compare requires BASELINE and CANDIDATE paths"))

      (when (seq extra)
        (invalid-args (str command " takes no positional arguments"))))))

(defn parse-args
  "Parses and validates one runner command without loading a catalog, build,
  server, browser or renderer. Options are resolved before any run home."
  [args]
  (let [args (cond-> args (= "--" (first args)) rest)
        {:keys [arguments options errors summary]} (parse-opts args cli-options)
        command (first arguments)
        flags   (supplied-flags args)]
    (when (seq errors)
      (invalid-args (str/join "\n" errors)))
    (when-not (:help options)
      (when-not (and (= 1 (count (take 1 arguments)))
                     (contains? #{"build" "purge" "run" "compare"} command))
        (invalid-args "expected build, purge, run or compare; try --help"))
      (check-command-flags! command flags)
      (check-arguments! command arguments)
      (when (and (= command "purge") (:only-builds options) (:only-runs options))
        (invalid-args "--only-builds and --only-runs are mutually exclusive"))
      (when (and (= command "run")
                 (:build-id options)
                 (some-flags? flags ["--default-features" "--no-default-features" "--features"]))
        (invalid-args "--build cannot be combined with feature-selection flags")))
    {:command command
     :arguments arguments
     :options options
     :summary summary}))

(defn- print-error
  [cause]
  (.error js/console (or (ex-message cause) (str cause)))
  (when-let [data (ex-data cause)]
    (.error js/console (pr-str data))))

(defn ^:async -main
  [& args]
  (try
    (let [{:keys [command arguments options summary] :as request} (parse-args args)]
      (cond
        (:help options)
        (println (usage summary))

        (= command "compare")
        (println (:text (compare/execute (second arguments) (nth arguments 2)
                                         (select-keys options [:cases :force]))))

        :else
        (let [^js commands (js/require (path/join js/__dirname "commands.cjs"))]
          (set! (.-exitCode js/process)
                (await (.execute commands
                                 (transit/encode-str (select-keys request [:command :options]))))))))
    (catch :default cause
      (print-error cause)
      (when (= ::invalid-args (:type (ex-data cause)))
        (.error js/console "Try --help for usage."))
      (set! (.-exitCode js/process) 1))))

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
   [benches.render-wasm.feature-plan :as feature-plan]
   [benches.render-wasm.node.compare :as compare]
   [benches.render-wasm.node.log :as log]
   [benches.render-wasm.node.options :as options]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]))

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
   ["-q" "--quiet" "Only warnings, errors and results"]
   ["-v" "--verbose" "Show debug details and raw build output"]
   [nil "--[no-]color" "Colorize terminal output; default auto" :default nil]
   [nil "--[no-]default-features" "Enable Cargo defaults" :default true]
   [nil "--features FEATURES" "Comma-separated functional Cargo features"
    :parse-fn options/parse-feature-list]
   [nil "--max-features N" "ab feature limit (1..6)"
    :default feature-plan/default-max-features
    :parse-fn parse-number
    :validate [#(and (js/Number.isSafeInteger %) (<= 1 % 6)) "must be an integer from 1 to 6"]]
   [nil "--confirm" "Confirm ab with >8 configurations or an empty filter" :default false]
   [nil "--dry-run" "Validate and print the ab plan without executing" :default false]
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
   [nil "--only-comparisons" "Purge managed feature comparisons"]
   [nil "--cases A,B" "Select one baseline,candidate pair"]
   [nil "--allow-build-differences" "Allow recorded build differences"]
   [nil "--allow-scene-differences" "Allow scene inputs and fingerprint content differences"]
   [nil "--allow-all-differences" "Allow all comparison compatibility differences"]])

(defn usage
  [summary]
  (str "Usage: runner build [options]\n"
       "       runner purge [--only-builds | --only-runs | --only-comparisons]\n"
       "       runner run [options]\n"
       "       runner ab --features A,B [--filter SUBSTR] [--max-features N]\n"
       "              [--confirm] [--dry-run] [run and comparison options]\n"
       "       runner compare BASELINE CANDIDATE [--cases A,B]\n"
       "              [--allow-build-differences] [--allow-scene-differences]\n"
       "              [--allow-all-differences]\n\n"
       "build prints Build <id> and Output <absolute path> on stdout. Exit 0 means\n"
       "ready; errors retain the build.transit and build.log for that ID.\n"
       "run prints Run <id>, Output <absolute path> and Report on stdout.\n"
       "Progress and diagnostics go to stderr. -q/--quiet hides progress;\n"
       "-v/--verbose includes raw build output. --[no-]color overrides terminal\n"
       "color detection. File logs receive all levels without logger colors.\n"
       "Builds and managed runs live under target/performance/render-wasm.\n"
       "compare reads saved schema:run records and starts no build, host or browser.\n"
       "Build and scene permissions combine; operation and runtime checks stay strict.\n"
       "All-differences permits every compatibility difference. Reports retain them.\n\n"
       "ab uses every on/off configuration with Cargo defaults disabled; declared\n"
       "defaults must be empty. --max-features defaults to 3 (range 1..6).\n"
       "Use --confirm above eight configurations or with an empty filter.\n"
       "--dry-run checks metadata, cases and output freshness without confirmation,\n"
       "output creation, compilation or browser startup. ab accepts run budgets,\n"
       "timeouts, seed, filter, output and screenshots, plus comparison permissions.\n"
       "It rejects --build, --remove, --cases and --[no-]default-features.\n"
       "ab prints Comparison <id>, Output <path> and Status <status> on stdout;\n"
       "feature-comparison.log records comparison progress. Diagnostics and\n"
       "failures go to stderr. --dry-run prints only the plan without creating\n"
       "the comparison home. Managed output lives under\n"
       "feature-comparisons/<ab-id>/feature-comparison.transit.\n\n"
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
  #{"--only-builds" "--only-runs" "--only-comparisons"})

(def ^:private ab-flags
  #{"--max-features" "--confirm" "--dry-run"})

(def ^:private compare-flags
  #{"--cases" "--allow-build-differences" "--allow-scene-differences"
    "--allow-all-differences"})

(defn- invalid-args
  [message]
  (throw (ex-info message {:type ::invalid-args})))

(defn- some-flags?
  [flags group]
  (boolean (some flags group)))

(defn- check-command-flags!
  [command flags]
  (when (and (not= command "ab") (some-flags? flags ab-flags))
    (invalid-args "ab planning options require ab"))
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

      "ab"
      (do
        (when purge? (invalid-args "purge category flags require purge"))
        (when (some-flags? flags ["--build" "--remove" "--cases"
                                  "--default-features" "--no-default-features"])
          (invalid-args "ab rejects --build, --remove, --cases and --[no-]default-features")))

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
    (when (and (:quiet options) (:verbose options))
      (invalid-args "--quiet and --verbose are mutually exclusive"))
    (when-not (:help options)
      (when-not (and (= 1 (count (take 1 arguments)))
                     (contains? #{"build" "purge" "run" "ab" "compare"} command))
        (invalid-args "expected build, purge, run, ab or compare; try --help"))
      (check-command-flags! command flags)
      (check-arguments! command arguments)
      (when (and (= command "purge")
                 (> (count (filter options [:only-builds :only-runs :only-comparisons])) 1))
        (invalid-args "--only-builds, --only-runs and --only-comparisons are mutually exclusive"))
      (when (and (= command "run")
                 (:build-id options)
                 (some-flags? flags ["--default-features" "--no-default-features" "--features"]))
        (invalid-args "--build cannot be combined with feature-selection flags"))
      (when (= command "ab")
        (try
          (feature-plan/plan (:features options []) options)
          (catch :default cause
            (invalid-args (ex-message cause))))))
    {:command command
     :arguments arguments
     :options (cond-> options (= command "ab") (assoc :default-features false))
     :summary summary}))

(defn ^:async -main
  [& args]
  (log/configure {})
  (try
    (let [{:keys [command arguments options summary] :as request} (parse-args args)]
      (log/configure {:level (options/console-level options)
                      :color (options/color-mode options)})
      (cond
        (:help options)
        (log/result (usage summary))

        (= command "compare")
        (log/result (:text (compare/execute (second arguments) (nth arguments 2)
                                            (select-keys options [:cases :allow-build-differences
                                                                  :allow-scene-differences
                                                                  :allow-all-differences]))))

        :else
        (let [^js commands (js/require (path/join js/__dirname "commands.cjs"))]
          (set! (.-exitCode js/process)
                (await (.execute commands
                                 (transit/encode-str (select-keys request [:command :options]))))))))
    (catch :default cause
      (let [data (ex-data cause)]
        (log/error {:message (str (or (ex-message cause) (str cause))
                                  "\nTry --help for usage.")
                    :fields (into (filterv (comp some? second)
                                           [[:type (:type data)] [:baseline (:baseline data)]
                                            [:candidate (:candidate data)]])
                                  (map #(vector :difference (:field %)) (:mismatches data)))
                    :tail? false}))
      (set! (.-exitCode js/process) 1))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns performance-tests.render-wasm.cli-test
  (:require
   [app.common.schema :as sm]
   [benches.render-wasm.node.cli.request :as request]
   [benches.render-wasm.node.cli.resolve :as cli]
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]))

(def ^:private measurement-defaults
  {:seed 42 :filter nil :warmups 3 :repetitions 10
   :timeout-ms 30000 :startup-timeout-ms 30000 :output-dir nil
   :screenshots false :require-gpu false})

(def ^:private comparison-defaults
  {:allow-build-differences false :allow-scene-differences false
   :allow-all-differences false})

(defn- resolved
  [command options]
  {:command command :console {:level :info :color :auto} :options options})

(defn- failure
  [argv]
  (try
    (cli/parse-resolve argv)
    nil
    (catch :default cause cause)))

(t/deftest commands-resolve-complete-requests
  (doseq [[argv expected]
          [[["build"]
            (resolved "build" {:default-features true :features [] :build-timeout-ms 300000})]
           [["run"]
            (resolved "run" (assoc measurement-defaults :remove false
                                   :build {:kind :prepare :default-features true
                                           :features [] :build-timeout-ms 300000}))]
           [["run" "--build" "ready-build"]
            (resolved "run" (assoc measurement-defaults :remove false
                                   :build {:kind :existing :id "ready-build"}))]
           [["ab" "--features" "cache" "--dry-run"]
            (resolved "ab" (merge measurement-defaults comparison-defaults
                                  {:features ["cache"] :max-features 3 :confirm false
                                   :dry-run true :build-timeout-ms 300000}))]
           [["compare" "base" "candidate"]
            (resolved "compare" (assoc comparison-defaults :baseline-path "base"
                                       :candidate-path "candidate" :case-pair nil))]
           [["compare" "base" "candidate" "--allow-build-differences"
             "--allow-scene-differences" "--allow-all-differences"]
            (resolved "compare" {:baseline-path "base" :candidate-path "candidate" :case-pair nil
                                 :allow-build-differences true :allow-scene-differences true
                                 :allow-all-differences true})]
           [["ab" "--features" "cache" "--filter" "rects" "--require-gpu" "--screenshots"
             "--allow-build-differences" "--allow-scene-differences" "--allow-all-differences"]
            (resolved "ab" (assoc measurement-defaults :filter "rects" :require-gpu true
                                  :screenshots true :features ["cache"] :max-features 3
                                  :confirm false :dry-run false :build-timeout-ms 300000
                                  :allow-build-differences true :allow-scene-differences true
                                  :allow-all-differences true))]
           [["merge" "base" "extra"]
            (resolved "merge" {:base-path "base" :additional-path "extra" :output-dir nil})]
           [["merge" " ./base " " ./extra " "--output-dir" " ./merged "]
            (resolved "merge" {:base-path " ./base " :additional-path " ./extra "
                               :output-dir " ./merged "})]
           [["purge" "comparisons"] (resolved "purge" {:target :comparisons})]
           [["--help"] (resolved "help" {:topic nil})]]]
    (t/testing (pr-str argv)
      (t/is (= expected (cli/parse-resolve argv))))))

(t/deftest console-flags-compose-with-every-command
  (doseq [argv [["build"] ["run"] ["ab" "--features" "cache" "--dry-run"]
                ["compare" "base" "candidate"] ["merge" "base" "extra"]
                ["purge" "runs"] ["--help"]]
          [flags console] [[["-q" "--no-color"] {:level :warn :color :never}
                            [["-v" "--color"] {:level :debug :color :always}]]]]
    (t/is (= console (:console (cli/parse-resolve (into argv flags))))))
  (doseq [argv [["run" "-qv"] ["build" "--quiet" "--verbose"]
                ["compare" "--help" "-qv"] ["--help" "-qv"]]]
    (let [error (failure argv)]
      (t/is (= ::cli/invalid-args (:type (ex-data error))))
      (t/is (str/includes? (ex-message error) "mutually exclusive")))))

(t/deftest explicit-build-and-measurement-values-survive-resolution
  (t/is (= (resolved "build" {:default-features false :features ["a" "b"]
                              :build-timeout-ms 1000})
           (cli/parse-resolve ["--" "build" "--no-default-features"
                               "--features=b,a,b" "--build-timeout-ms" "1e3"])))
  (t/is (= {:command "run" :console {:level :info :color :never}
            :options {:build {:kind :prepare :default-features false
                              :features ["a" "b"] :build-timeout-ms 5000}
                      :seed 0 :filter " rects " :warmups 0 :repetitions 2
                      :timeout-ms 1000 :startup-timeout-ms 2000 :output-dir " ./runs "
                      :screenshots true :remove true :require-gpu true}}
           (cli/parse-resolve
            ["run" "--no-default-features" "--features" " b,a,b "
             "--build-timeout-ms" "5000" "--seed" "0" "--filter" " rects "
             "--warmups" "0" "--repetitions" "2" "--timeout-ms" "1000"
             "--startup-timeout-ms" "2000" "--output-dir" " ./runs "
             "--screenshots" "--remove" "--require-gpu" "--no-color"]))))

(t/deftest retained-build-conflicts-use-supplied-flags
  (doseq [flags [["--features" "cache"] ["--default-features"]
                 ["--no-default-features"] ["--build-timeout-ms=300000"]]
          argv [(into ["run" "--build" "ready"] flags)
                (into (into ["run"] flags) ["--build=ready"])]]
    (t/is (str/includes? (ex-message (failure argv)) "--build cannot be combined")))
  (t/is (= (resolved "run" (assoc measurement-defaults :remove false :warmups 0
                                  :build {:kind :existing :id "ready"}))
           (cli/parse-resolve ["run" "--build" "ready" "--warmups" "0"]))))

(t/deftest parsers-reject-options-owned-by-other-commands
  (doseq [[argv flags]
          [[["build"] ["--warmups" "--cases" "--confirm"]]
           [["run"] ["--cases" "--dry-run" "--allow-build-differences"]]
           [["ab" "--features" "cache" "--dry-run"]
            ["--build" "--remove" "--default-features" "--no-default-features" "--cases"]]
           [["compare" "base" "candidate"] ["--output-dir" "--seed" "--features"]]
           [["merge" "base" "extra"] ["--warmups" "--cases" "--allow-all-differences"]]
           [["purge" "runs"] ["--features" "--seed" "--only-runs"]]]
          flag flags]
    (doseq [args [(conj argv flag) (conj argv "--help" flag)]]
      (let [error (failure args)]
        (t/is (= ::cli/invalid-args (:type (ex-data error))))
        (t/is (str/includes? (ex-message error) "Unknown option"))))))

(t/deftest command-first-syntax-and-positional-arguments-are-checked
  (doseq [argv [[] ["unknown" "--help"] ["--help" "run"] ["-q" "build"]
                ["build" "extra"] ["run" "extra"]
                ["compare"] ["compare" "one"] ["compare" "one" "two" "three"]
                ["compare" "" "candidate"] ["merge" "base"] ["merge" "base" " "]
                ["run" "--build" "../escape"] ["run" "--output-dir" " "]]]
    (t/testing (pr-str argv)
      (t/is (= ::cli/invalid-args (:type (ex-data (failure argv)))))))
  (doseq [target ["builds" "runs" "comparisons" "all"]]
    (t/is (= (resolved "purge" {:target (keyword target)})
             (cli/parse-resolve ["purge" target]))))
  (doseq [args [[] ["unknown"] ["runs" "builds"]]]
    (let [error (failure (into ["purge"] args))]
      (t/is (= ::cli/invalid-args (:type (ex-data error))))
      (t/is (str/includes? (ex-message error) "runner purge builds|runs|comparisons|all")))))

(t/deftest argument-errors-include-command-and-validation-details
  (let [data (ex-data (failure ["unknown" "--help"]))]
    (t/is (= "unknown" (:command data)))
    (t/is (= "runner COMMAND [options]" (:usage data))))
  (doseq [[argv path] [[["compare" "" "candidate"] [:options :baseline-path]]
                       [["merge" "base" " "] [:options :additional-path]]]]
    (let [data (ex-data (failure argv))]
      (t/is (= ::cli/invalid-args (:type data)))
      (t/is (= ::request/invalid-request (:cause-type data)))
      (t/is (some #(= path (:in %)) (:errors (::sm/explain data)))))))

(t/deftest literal-paths-selectors-and-option-looking-values-are-preserved
  (t/is (= (resolved "compare" (assoc comparison-defaults :baseline-path " ./base "
                                      :candidate-path " ./candidate "
                                      :case-pair [" rects/load " "other/load"]))
           (cli/parse-resolve ["compare" " ./base " " ./candidate "
                               "--cases" " rects/load ,other/load"])))
  (t/is (= (resolved "compare" (assoc comparison-defaults :baseline-path "--seed"
                                      :candidate-path "-candidate" :case-pair nil))
           (cli/parse-resolve ["compare" "--" "--seed" "-candidate"])))
  (doseq [text ["" " " "--allow-all-differences" "--" "-h"]]
    (t/is (= (resolved "run" (assoc measurement-defaults :filter text :remove false
                                    :build {:kind :prepare :default-features true
                                            :features [] :build-timeout-ms 300000}))
             (cli/parse-resolve ["run" "--filter" text])))))

(t/deftest malformed-selector-pairs-fail-before-record-loading
  (doseq [text ["" "one" "one,two,three" ",two" "one," " ,two" "one, "]]
    (let [error (failure ["compare" "base" "candidate" (str "--cases=" text)])]
      (t/is (= ::cli/invalid-args (:type (ex-data error))))
      (t/is (str/includes? (ex-message error) "--cases"))))
  (t/is (= ::cli/invalid-args
           (:type (ex-data (failure ["compare" "base" "candidate" "--cases"]))))))

(t/deftest numeric-values-use-integer-and-timer-bounds
  (doseq [[flag values]
          [["--seed" ["0" "4294967295"]]
           ["--warmups" ["0" "9007199254740991"]]
           ["--repetitions" ["1" "9007199254740991"]]
           ["--timeout-ms" ["1" "2147483647"]]
           ["--startup-timeout-ms" ["1" "2147483647"]]
           ["--build-timeout-ms" ["1" " 1000 " "1e3" "0x3e8" "0o1750" "0b1111101000" "2147483647"]]]
          value values]
    (t/is (= (js/Number value)
             (let [options (:options (cli/parse-resolve ["run" flag value]))]
               (if (= flag "--build-timeout-ms")
                 (get-in options [:build :build-timeout-ms])
                 (get options (keyword (subs flag 2))))))))
  (doseq [[flag values]
          [["--seed" ["-1" "4294967296" "1.5"]]
           ["--warmups" ["-1" "9007199254740992" "0.5"]]
           ["--repetitions" ["0" "9007199254740992" "1.5"]]
           ["--timeout-ms" ["0" "2147483648" "1.5"]]
           ["--startup-timeout-ms" ["0" "2147483648"]]
           ["--build-timeout-ms" ["0" "2147483648" "1.5" "1000ms"]]]
          value (concat values ["" " " "NaN" "Infinity"])]
    (t/testing (str flag " " value)
      (t/is (= ::cli/invalid-args (:type (ex-data (failure ["run" flag value]))))))))

(t/deftest feature-selection-and-ab-policy-use-domain-rules
  (doseq [command ["build" "run" "ab"]
          [feature message] [["stats" "diagnostic Cargo features"]
                             ["profile" "diagnostic Cargo features"]
                             ["profile-macros" "diagnostic Cargo features"]
                             ["profile-raf" "diagnostic Cargo features"]
                             ["" "invalid Cargo feature selection"]
                             ["a,,b" "invalid Cargo feature selection"]
                             ["a/b" "invalid Cargo feature selection"]]]
    (let [argv  (cond-> [command "--features" feature] (= command "ab") (conj "--dry-run"))
          error (failure argv)]
      (t/is (= ::cli/invalid-args (:type (ex-data error))))
      (t/is (str/includes? (ex-message error) message))))
  (doseq [argv [["ab"] ["ab" "--features" "cache"]
                ["ab" "--features" "cache" "--filter" " "]
                ["ab" "--features" "a,b,c,d" "--dry-run"]
                ["ab" "--features" "a,b,c,d" "--max-features" "4" "--filter" "rects"]]]
    (t/is (= ::cli/invalid-args (:type (ex-data (failure argv))))))
  (doseq [limit ["" " " "0" "7" "1.5" "NaN" "Infinity" "3x"]]
    (t/is (= ::cli/invalid-args
             (:type (ex-data (failure ["ab" "--features" "cache" "--dry-run"
                                       "--max-features" limit]))))))
  (doseq [flags [["--confirm"] ["--dry-run"] ["--filter" "rects"]]]
    (t/is (= ["a" "b" "c"]
             (get-in (cli/parse-resolve (into ["ab" "--features" "c,b,a,c"] flags))
                     [:options :features]))))
  (let [error (failure ["ab" "--features" "cache"])]
    (t/is (str/includes? (ex-message (ex-cause error)) "--confirm"))))

(t/deftest help-skips-required-command-arguments-and-keeps-syntax-checks
  (doseq [command ["build" "run" "ab" "compare" "merge" "purge"]]
    (t/is (= (resolved "help" {:topic command})
             (cli/parse-resolve [command "--help"]))))
  (t/is (= (assoc (resolved "help" {:topic "compare"})
                  :console {:level :debug :color :never})
           (cli/parse-resolve ["compare" "--help" "--verbose" "--no-color"])))
  (doseq [argv [["--help" "--unknown"] ["run" "--help" "--warmups" "bad"]
                ["compare" "--help" "--cases="] ["ab" "--help" "--features"]]]
    (t/is (= ::cli/invalid-args (:type (ex-data (failure argv)))))))

(t/deftest help-shows-command-options-and-schema-defaults
  (doseq [[topic flags]
          [["build" #{"--[no-]default-features" "--features" "--build-timeout-ms"}]
           ["run" #{"--build" "--[no-]default-features" "--features" "--build-timeout-ms"
                    "--seed" "--filter" "--warmups" "--repetitions" "--timeout-ms"
                    "--startup-timeout-ms" "--output-dir" "--screenshots" "--require-gpu" "--remove"}]
           ["ab" #{"--features" "--max-features" "--confirm" "--dry-run" "--build-timeout-ms"
                   "--seed" "--filter" "--warmups" "--repetitions" "--timeout-ms"
                   "--startup-timeout-ms" "--output-dir" "--screenshots" "--require-gpu"
                   "--allow-build-differences" "--allow-scene-differences" "--allow-all-differences"}]
           ["compare" #{"--cases" "--allow-build-differences"
                        "--allow-scene-differences" "--allow-all-differences"}]
           ["merge" #{"--output-dir"}]
           ["purge" #{}]]]
    (let [text (cli/get-help topic)]
      (t/is (str/includes? text (str "Usage: runner " topic)))
      (t/is (= (into #{"--help" "--quiet" "--verbose" "--[no-]color"} flags)
               (set (re-seq #"--(?:\[no-\])?[a-z][a-z-]*" text))))))
  (t/is (= ["build" "run" "ab" "compare" "merge" "purge"]
           (mapv second (re-seq #"(?m)^  runner ([a-z]+)" (cli/get-help nil)))))
  (doseq [[topic pattern] [["build" #"--build-timeout-ms MS\s+300000"]
                           ["run" #"--warmups N\s+3"]
                           ["run" #"--seed N\s+42"]
                           ["run" #"--repetitions N\s+10"]
                           ["run" #"--features FEATURES\s+\[\]"]
                           ["run" #"--\[no-\]default-features\s+true"]
                           ["run" #"--build-timeout-ms MS\s+300000"]
                           ["ab" #"--max-features N\s+3"]
                           ["merge" #"--output-dir DIR\s+nil"]
                           ["compare" #"--cases A,B\s+nil"]]]
    (t/is (re-find pattern (cli/get-help topic)))))

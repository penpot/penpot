;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns performance-tests.render-wasm.runner-test
  (:require
   [benches.render-wasm.node.process :as process]
   [benches.render-wasm.node.runner :as runner]
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]))

(t/deftest build-parses-defaults-and-explicit-functional-feature-selection
  (let [{:keys [command options]} (runner/parse-args ["build"])]
    (t/is (= "build" command))
    (t/is (true? (:default-features options)))
    (t/is (= process/default-build-timeout-ms (:build-timeout-ms options))))
  (let [{:keys [command options]} (runner/parse-args
                                   ["--" "build" "--no-default-features"
                                    "--features" "functional_b,functional-a,functional_b"
                                    "--build-timeout-ms" "1250"])]
    (t/is (= "build" command))
    (t/is (= {:default-features false
              :features ["functional-a" "functional_b"]
              :build-timeout-ms 1250}
             (select-keys options [:default-features :features :build-timeout-ms])))))

(t/deftest diagnostic-and-malformed-features-fail-at-the-parser-boundary
  (doseq [feature ["stats" "profile" "profile-macros" "profile-raf"]]
    (t/is (thrown-with-msg? js/Error #"diagnostic Cargo features"
                            (runner/parse-args ["build" "--features" (str "functional," feature)]))))
  (doseq [feature ["" "functional,,other" "functional/other"]]
    (t/is (thrown-with-msg? js/Error #"invalid Cargo feature selection"
                            (runner/parse-args ["build" "--features" feature]))))
  (t/is (thrown? js/Error (runner/parse-args ["build" "--features"]))))

(t/deftest preparation-budget-requires-a-complete-positive-safe-integer-number
  ;; Number accepts whitespace and exponent notation when the result is an
  ;; integer; these are valid CLI budgets, not partial numeric parses.
  (doseq [[text expected] [["1" 1] [" 1000 " 1000] ["1e3" 1000]]]
    (t/is (= expected (get-in (runner/parse-args ["build" "--build-timeout-ms" text])
                              [:options :build-timeout-ms]))))
  (doseq [text ["" "0" "-1" "1.5" "1000ms" "NaN" "Infinity" "9007199254740992"]]
    (t/is (thrown-with-msg? js/Error #"Must be a positive integer"
                            (runner/parse-args ["build" "--build-timeout-ms" text]))))
  (t/is (thrown? js/Error (runner/parse-args ["build" "--build-timeout-ms"]))))

(t/deftest purge-categories-and-build-flags-cannot-be-mixed
  (doseq [[args selected] [[["purge"] {}]
                           [["purge" "--only-builds"] {:only-builds true}]
                           [["purge" "--only-runs"] {:only-runs true}]]]
    (let [{:keys [command options]} (runner/parse-args args)]
      (t/is (= "purge" command))
      (t/is (= selected (select-keys options [:only-builds :only-runs])))))
  (t/is (thrown-with-msg? js/Error #"mutually exclusive"
                          (runner/parse-args ["purge" "--only-builds" "--only-runs"])))
  (doseq [flag ["--only-builds" "--only-runs"]]
    (t/is (thrown-with-msg? js/Error #"purge category flags require purge"
                            (runner/parse-args ["build" flag]))))
  (doseq [args [["purge" "--default-features"]
                ["purge" "--no-default-features"]
                ["purge" "--features" "functional"]
                ["purge" "--build-timeout-ms" "1000"]
                ["purge" "--features=functional"]
                ["purge" "--build-timeout-ms=1000"]]]
    (t/is (thrown-with-msg? js/Error #"build options require build"
                            (runner/parse-args args)))))

(t/deftest help-needs-no-command-and-other-command-lines-must-be-valid
  (doseq [args [["--help"] ["-h"] ["build" "--help"] ["run" "--help"]
                ["compare" "--help"]]]
    (let [{:keys [options summary]} (runner/parse-args args)
          help                    (runner/usage summary)]
      (t/is (true? (:help options)))
      (t/is (str/includes? help "runner build"))
      (t/is (str/includes? help "runner purge"))
      (t/is (str/includes? help "runner run"))
      (t/is (str/includes? help "runner compare"))
      (t/is (str/includes? help "--build-timeout-ms"))
      (t/is (str/includes? help "--compare-cases"))))
  (t/is (thrown-with-msg? js/Error #"expected build, purge, run or compare"
                          (runner/parse-args [])))
  (doseq [args [["unknown"] ["build" "purge"] ["build" "--unknown"]
                ["run" "extra"] ["compare" "only-one"]]]
    (t/is (thrown? js/Error (runner/parse-args args)))))

(t/deftest run-resolves-default-and-explicit-options-before-resources
  (let [{:keys [command options arguments]} (runner/parse-args ["run"])]
    (t/is (= "run" command))
    (t/is (= ["run"] arguments))
    (t/is (= {:seed 42 :warmups 3 :repetitions 10 :timeout-ms 30000
              :build-timeout-ms 300000 :startup-timeout-ms 30000}
             (select-keys options [:seed :warmups :repetitions :timeout-ms
                                   :build-timeout-ms :startup-timeout-ms])))
    (t/is (nil? (:build-id options)))
    (t/is (nil? (:filter options)))
    (t/is (not (:remove options)))
    (t/is (not (:screenshots options))))
  (let [{:keys [options]} (runner/parse-args
                           ["run" "--seed" "7" "--filter" "rects"
                            "--warmups" "0" "--repetitions" "2"
                            "--timeout-ms" "100" "--build-timeout-ms" "200"
                            "--startup-timeout-ms" "300" "--output-dir" "out"
                            "--remove" "--screenshots"])]
    (t/is (= {:seed 7 :filter "rects" :warmups 0 :repetitions 2
              :timeout-ms 100 :build-timeout-ms 200 :startup-timeout-ms 300
              :output-dir "out" :remove true :screenshots true}
             (select-keys options [:seed :filter :warmups :repetitions
                                   :timeout-ms :build-timeout-ms
                                   :startup-timeout-ms :output-dir
                                   :remove :screenshots])))))

(t/deftest retained-runs-reject-feature-selection-and-build-runs-accept-it
  (t/is (= "retained-id" (get-in (runner/parse-args ["run" "--build" "retained-id"])
                                 [:options :build-id])))
  (doseq [args [["run" "--build" "retained-id" "--no-default-features"]
                ["run" "--no-default-features" "--build" "retained-id"]
                ["run" "--build" "retained-id" "--features" "functional"]
                ["run" "--build" "retained-id" "--default-features"]]]
    (t/is (thrown-with-msg? js/Error #"cannot be combined"
                            (runner/parse-args args))))
  (let [{:keys [options]} (runner/parse-args
                           ["run" "--no-default-features" "--features" "functional"])]
    (t/is (false? (:default-features options)))
    (t/is (= ["functional"] (:features options)))))

(t/deftest compare-takes-two-file-paths-and-its-own-options
  (let [{:keys [command arguments options]}
        (runner/parse-args ["compare" "baseline.transit" "candidate.transit"])]
    (t/is (= "compare" command))
    (t/is (= ["compare" "baseline.transit" "candidate.transit"] arguments))
    (t/is (nil? (:compare-cases options)))
    (t/is (not (:force options))))
  (let [{:keys [options]}
        (runner/parse-args ["compare" "a.transit" "b.transit"
                            "--compare-cases" "rects/pan,paths/pan" "--force"])]
    (t/is (= "rects/pan,paths/pan" (:compare-cases options)))
    (t/is (true? (:force options)))))

(t/deftest commands-reject-flags-owned-by-other-commands
  (doseq [[args message]
          [[["run" "--only-builds"] #"purge category flags require purge"]
           [["run" "--compare-cases" "a,b"] #"compare options require compare"]
           [["compare" "a" "b" "--seed" "1"] #"run options require run"]
           [["compare" "a" "b" "--features" "functional"] #"build options require build"]
           [["build" "--build" "retained"] #"run options require run"]
           [["build" "--only-runs"] #"purge category flags require purge"]
           [["purge" "--seed" "1"] #"run options require run"]
           [["purge" "--compare-cases" "a,b"] #"compare options require compare"]]]
    (t/is (thrown-with-msg? js/Error message (runner/parse-args args)))))

(t/deftest numeric-and-path-options-reject-invalid-bounds
  (doseq [args [["run" "--seed" "4294967296"]
                ["run" "--seed" "-1"]
                ["run" "--warmups" "-1"]
                ["run" "--warmups" "1.5"]
                ["run" "--repetitions" "0"]
                ["run" "--timeout-ms" "0"]
                ["run" "--timeout-ms" "2147483648"]
                ["run" "--startup-timeout-ms" "0"]
                ["run" "--build" ""]
                ["run" "--output-dir" ""]]]
    (t/is (thrown? js/Error (runner/parse-args args))))
  (t/is (= 4294967295 (:seed (:options (runner/parse-args ["run" "--seed" "4294967295"])))))
  (t/is (= 0 (:warmups (:options (runner/parse-args ["run" "--warmups" "0"]))))))

(t/deftest option-looking-values-and-filenames-are-not-command-flags
  (let [parsed (runner/parse-args ["compare" "--" "--seed" "candidate.transit"])]
    (t/is (= ["compare" "--seed" "candidate.transit"] (:arguments parsed))))
  (let [parsed (runner/parse-args ["run" "--filter" "--force"])]
    (t/is (= "--force" (:filter (:options parsed))))))

(t/deftest numeric-options-reject-blank-values
  (doseq [args [["run" "--seed" ""]
                ["run" "--warmups" " "]
                ["run" "--repetitions" ""]
                ["run" "--timeout-ms" " "]]]
    (t/is (thrown? js/Error (runner/parse-args args)))))

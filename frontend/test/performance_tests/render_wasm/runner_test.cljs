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
  (doseq [args [["--help"] ["-h"] ["build" "--help"]]]
    (let [{:keys [options summary]} (runner/parse-args args)
          help                    (runner/usage summary)]
      (t/is (true? (:help options)))
      (t/is (str/includes? help "runner build"))
      (t/is (str/includes? help "runner purge"))
      (t/is (str/includes? help "--build-timeout-ms"))))
  (t/is (thrown-with-msg? js/Error #"expected build or purge"
                          (runner/parse-args [])))
  (doseq [args [["unknown"] ["build" "purge"] ["build" "--unknown"]]]
    (t/is (thrown? js/Error (runner/parse-args args)))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns performance-tests.render-wasm.pilot-test
  (:require
   [benches.render-wasm.node.pilot :as pilot]
   [cljs.test :as t :include-macros true]))

(t/deftest diagnostic-budget-is-fixed-and-explicit
  (let [defaults (pilot/parse-args [])
        explicit (pilot/parse-args ["--warmups" "0" "--attempts" "1"])]
    (t/is (= defaults explicit))
    (t/is (= {:warmups 0 :attempts 1 :timeout-ms 30000
              :build-timeout-ms 300000 :startup-timeout-ms 30000}
             (select-keys defaults [:warmups :attempts :timeout-ms :build-timeout-ms :startup-timeout-ms]))))
  (doseq [args [["--warmups" "1"] ["--attempts" "0"] ["--attempts" "2"]]]
    (t/is (thrown-with-msg? js/Error #"requires --warmups 0 and --attempts 1"
                            (pilot/parse-args args)))))

(t/deftest retained-reuse-rejects-every-explicit-feature-selection
  (t/is (= "retained-id" (:build-id (pilot/parse-args ["--build" "retained-id"]))))
  (doseq [args [["--build" "retained-id" "--no-default-features"]
                ["--no-default-features" "--build" "retained-id"]
                ["--build" "retained-id" "--features" ""]
                ["--features" "functional" "--build" "retained-id"]]]
    (t/is (thrown-with-msg? js/Error #"cannot be combined"
                            (pilot/parse-args args)))))

(t/deftest numeric-options-reject-prefix-parsing-and-invalid-bounds
  (doseq [flag ["--seed" "--timeout-ms" "--build-timeout-ms" "--startup-timeout-ms"
                "--warmups" "--attempts"]
          value ["1ms" "1.5" "1e3" "NaN" "-1" "9007199254740992"]]
    (t/is (thrown? js/Error (pilot/parse-args [flag value]))))
  (doseq [args [["--seed" "4294967296"] ["--timeout-ms" "0"]
                ["--build-timeout-ms" "0"] ["--startup-timeout-ms" "0"]
                ["--timeout-ms" "2147483648"]]]
    (t/is (thrown? js/Error (pilot/parse-args args))))
  (t/is (= 4294967295 (:seed (pilot/parse-args ["--seed" "4294967295"])))))

(t/deftest diagnostic-options-keep-case-selection-for-the-retained-catalog
  (let [opts (pilot/parse-args ["--case" "retained-scene/operation" "--seed" "7"
                                "--timeout-ms" "100" "--build-timeout-ms" "200"
                                "--startup-timeout-ms" "300" "--no-default-features"
                                "--features" "first, second" "--screenshot" "/tmp/pilot.png"])]
    (t/is (= :retained-scene/operation (:case-id opts)))
    (t/is (= {:default-features? false :features ["first" "second"]} (:features opts)))
    (t/is (= {:seed 7 :timeout-ms 100 :build-timeout-ms 200 :startup-timeout-ms 300
              :screenshot "/tmp/pilot.png"}
             (select-keys opts [:seed :timeout-ms :build-timeout-ms :startup-timeout-ms :screenshot])))))

(t/deftest malformed-options-fail-at-the-cli-boundary
  (doseq [args [["--build"] ["--case"] ["--seed"] ["--screenshot"]
                ["--build" ""] ["--screenshot" ""] ["--case" "load"]
                ["--features" "first,,second"] ["--timeout-ms" "--seed" "42"]
                ["--unknown"]]]
    (t/is (thrown? js/Error (pilot/parse-args args))))
  (t/is (:help? (pilot/parse-args ["--help"])))
  (t/is (:help? (pilot/parse-args ["-h"]))))

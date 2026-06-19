;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.shell-test
  (:require
   [app.common.exceptions :as ex]
   [app.util.shell :as shell]
   [clojure.string :as str]
   [clojure.test :as t]))

(t/deftest exec-normal-completes
  (t/testing "normal process completes within timeout"
    (let [result (shell/exec! {}
                              :cmd ["echo" "hello"]
                              :timeout 10)]
      (t/is (= 0 (:exit result)))
      (t/is (str/includes? (:out result) "hello")))))

(t/deftest exec-captures-stderr
  (t/testing "stderr is captured separately"
    (let [result (shell/exec! {}
                              :cmd ["bash" "-c" "echo out; echo err >&2"]
                              :timeout 10)]
      (t/is (= 0 (:exit result)))
      (t/is (str/includes? (:out result) "out"))
      (t/is (str/includes? (:err result) "err")))))

(t/deftest exec-non-zero-exit
  (t/testing "non-zero exit code is captured"
    (let [result (shell/exec! {}
                              :cmd ["bash" "-c" "exit 42"]
                              :timeout 10)]
      (t/is (= 42 (:exit result))))))

(t/deftest exec-with-env
  (t/testing "environment variables are passed to the process"
    (let [result (shell/exec! {}
                              :cmd ["bash" "-c" "echo $MY_VAR"]
                              :env {"MY_VAR" "test-value"}
                              :timeout 10)]
      (t/is (= 0 (:exit result)))
      (t/is (str/includes? (:out result) "test-value")))))

(t/deftest exec-with-input
  (t/testing "stdin input is passed to the process"
    (let [result (shell/exec! {}
                              :cmd ["cat"]
                              :in "hello from stdin"
                              :timeout 10)]
      (t/is (= 0 (:exit result)))
      (t/is (str/includes? (:out result) "hello from stdin")))))

(t/deftest exec-timeout-kills-process
  (t/testing "process that exceeds timeout is killed and raises exception"
    (let [start (System/currentTimeMillis)]
      (try
        (shell/exec! {}
                     :cmd ["sleep" "60"]
                     :timeout 1)
        (t/is false "should have thrown")
        (catch Exception e
          (let [elapsed (- (System/currentTimeMillis) start)
                data    (ex-data e)]
            ;; Should complete quickly due to timeout, not wait 60s
            (t/is (< elapsed 10000) "process should be killed within ~1 second")
            (t/is (= :internal (:type data)))
            (t/is (= :process-timeout (:code data)))
            (t/is (= 1 (:timeout data)))))))))

(t/deftest exec-no-timeout-waits
  (t/testing "without timeout, process runs to completion"
    (let [result (shell/exec! {}
                              :cmd ["sleep" "0.1"]
                              :timeout nil)]
      (t/is (= 0 (:exit result))))))

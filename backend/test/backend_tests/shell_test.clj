;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.shell-test
  (:require
   [app.common.exceptions :as ex]
   [app.util.shell :as shell]
   [app.worker :as-alias wrk]
   [clojure.string :as str]
   [clojure.test :as t]
   [promesa.exec :as px]))

(def ^:private system
  {::wrk/executor (px/cached-executor)})

(t/deftest exec-normal-completes
  (t/testing "normal process completes within timeout"
    (let [result (shell/exec! system
                              :cmd ["echo" "hello"]
                              :timeout 10)]
      (t/is (= 0 (:exit result)))
      (t/is (str/includes? (:out result) "hello")))))

(t/deftest exec-captures-stderr
  (t/testing "stderr is captured separately"
    (let [result (shell/exec! system
                              :cmd ["bash" "-c" "echo out; echo err >&2"]
                              :timeout 10)]
      (t/is (= 0 (:exit result)))
      (t/is (str/includes? (:out result) "out"))
      (t/is (str/includes? (:err result) "err")))))

(t/deftest exec-non-zero-exit
  (t/testing "non-zero exit code is captured"
    (let [result (shell/exec! system
                              :cmd ["bash" "-c" "exit 42"]
                              :timeout 10)]
      (t/is (= 42 (:exit result))))))

(t/deftest exec-with-env
  (t/testing "environment variables are passed to the process"
    (let [result (shell/exec! system
                              :cmd ["bash" "-c" "echo $MY_VAR"]
                              :env {"MY_VAR" "test-value"}
                              :timeout 10)]
      (t/is (= 0 (:exit result)))
      (t/is (str/includes? (:out result) "test-value")))))

(t/deftest exec-with-input
  (t/testing "stdin input is passed to the process"
    (let [result (shell/exec! system
                              :cmd ["cat"]
                              :in "hello from stdin"
                              :timeout 10)]
      (t/is (= 0 (:exit result)))
      (t/is (str/includes? (:out result) "hello from stdin")))))

(t/deftest exec-timeout-kills-process
  (t/testing "process that exceeds timeout is killed and raises exception"
    (let [start (System/currentTimeMillis)]
      (try
        (shell/exec! system
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
    (let [result (shell/exec! system
                              :cmd ["sleep" "0.1"]
                              :timeout nil)]
      (t/is (= 0 (:exit result))))))

(t/deftest exec-prlimit-normal
  (t/testing "normal process completes within prlimit"
    (let [result (shell/exec! system
                              :cmd ["echo" "hello"]
                              :prlimit {:mem 256 :cpu 10}
                              :timeout 10)]
      (t/is (= 0 (:exit result)))
      (t/is (str/includes? (:out result) "hello")))))

(t/deftest exec-prlimit-cpu
  (t/testing "process exceeding CPU limit is killed"
    (let [result (shell/exec! system
                              :cmd ["bash" "-c" "while true; do :; done"]
                              :prlimit {:cpu 2}
                              :timeout 10)]
      (t/is (not= 0 (:exit result))))))

(t/deftest exec-prlimit-memory
  (t/testing "process exceeding memory limit is killed"
    ;; Use python3 to allocate more memory than the limit allows.
    ;; This test requires python3 to be available in the environment.
    (let [result (shell/exec! system
                              :cmd ["python3" "-c"
                                    "import sys; x = bytearray(600 * 1024 * 1024); sys.exit(0)"]
                              :prlimit {:mem 256}
                              :timeout 10)]
      ;; Should fail because 600 MiB > 256 MiB limit
      (t/is (not= 0 (:exit result))))))

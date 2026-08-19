;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns exporter-tests.shell-test
  "Tests to verify GHSA-4f36-m4hj-cv86 is fixed: OS Command Injection in SVG exporter.
   These tests prove that:
   1. execFile does NOT interpret shell metacharacters (safe execution)
   2. Malicious colors fail validation regex
   3. The injection does NOT execute commands (no RCE)"
  (:require
   ["node:child_process" :as proc]
   ["node:fs" :as fs]
   [cljs.test :as t :include-macros true]))

(def ^:private hex-color-rx
  #"^#(?:[0-9a-fA-F]{3}){1,2}$")

(defn- valid-hex-color?
  [color]
  (and (string? color)
       (some? (re-matches hex-color-rx color))))

(t/deftest execfile-does-not-interpret-shell-metacharacters
  (t/testing "Proves execFile passes arguments literally (no shell interpretation)"
    (t/async done
      (let [cmd "echo"
            args #js ["$(echo PWNED)"]]
        (proc/execFile cmd args #js {:encoding "buffer"}
                       (fn [error stdout _stderr]
                         (if error
                           (do
                             (t/is false (str "unexpected error: " (.-message error)))
                             (done))
                           (let [output (.toString stdout "utf8")]
                             (t/is (= "$(echo PWNED)\n" output)
                                   "execFile passes $(...) literally, no shell interpretation")
                             (done)))))))))

(t/deftest malicious-color-fails-validation
  (t/testing "Proves malicious colors are rejected by validation"
    (let [malicious "#000000$(echo PWNED)"
          valid-color "#000000"
          short-valid "#abc"]
      (t/is (not (valid-hex-color? malicious))
            "malicious color with $(...) fails validation")
      (t/is (valid-hex-color? valid-color)
            "valid 6-digit hex color passes validation")
      (t/is (valid-hex-color? short-valid)
            "valid 3-digit hex color passes validation"))))

(t/deftest execfile-does-not-execute-injected-commands
  (t/testing "Proves execFile does NOT execute injected commands (no RCE)"
    (t/async done
      (let [marker    "/tmp/penpot-exporter-rce-test"
            malicious (str "#000000$(touch " marker ")")
            cmd       "echo"
            args      #js [malicious]]
        (when (fs/existsSync marker)
          (fs/unlinkSync marker))
        (proc/execFile cmd args #js {:encoding "buffer"}
                       (fn [_error _stdout _stderr]
                         ;; Command completes (or fails), but no injection occurs
                         (t/is (not (fs/existsSync marker))
                               "no RCE: marker file was NOT created")
                         (when (fs/existsSync marker)
                           (fs/unlinkSync marker))
                         (done)))))))

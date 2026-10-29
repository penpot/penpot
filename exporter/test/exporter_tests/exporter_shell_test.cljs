;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.exporter-shell-test
  "The shell utilities of the new tree: every path explicit, no
  global tmpdir, over a scratch area of the test itself."
  (:require
   ["node:fs" :as fs]
   ["node:os" :as os]
   ["node:path" :as path]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [exporter.shell :as shell]))

(defn- scratch-dir
  []
  (path/join (os/tmpdir) "penpot-exporter-shell-test"))

(t/deftest ensure-dir-creates-and-returns
  (let [nested (path/join (scratch-dir) "nested" "deeper")]
    (t/is (= nested (shell/ensure-dir nested)))
    (t/is (true? (fs/existsSync nested)))
    (t/testing "a blank path is a wiring error, not an empty directory"
      (try
        (shell/ensure-dir "   ")
        (t/is false "ensure-dir should have rejected a blank path")
        (catch :default cause
          (t/is (= :tmpdir-path-missing (-> cause ex-data :code))))))))

(t/deftest tempfile-names-live-under-the-given-dir
  (let [dir  (shell/ensure-dir (scratch-dir))
        path (shell/tempfile dir :prefix "penpot.test." :suffix ".png")]
    (t/is (str/starts-with? path dir))
    (t/is (str/ends-with? path ".png"))
    (t/is (str/includes? path "penpot.test."))))

(t/deftest ^:async schedule-deletion-removes
  (try
    (let [dir   (shell/ensure-dir (scratch-dir))
          fpath (path/join dir "doomed.tmp")]
      (await (shell/write-file fpath "doomed"))
      (shell/schedule-deletion fpath 0.05)
      (await (js/Promise. (fn [resolve] (js/setTimeout resolve 300))))
      (t/is (false? (fs/existsSync fpath))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async move-write-and-read-roundtrip
  (try
    (let [dir  (shell/ensure-dir (scratch-dir))
          src  (path/join dir "src.tmp")
          dest (path/join dir "dest.tmp")]
      (await (shell/write-file src "bytes"))
      (await (shell/move src dest))
      (t/is (false? (fs/existsSync src)))
      (t/is (= "bytes" (str (await (shell/read-file dest))))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async run-cmd-resolves-stdout
  (try
    (let [out (await (shell/run-cmd "echo" "hello"))]
      (t/is (str/includes? (str out) "hello")))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async run-cmd-passes-arguments-literally
  ;; GHSA-4f36-m4hj-cv86 stays fixed: the wrapper runs execFile, so
  ;; shell metacharacters in args travel literally and never execute.
  (try
    (let [marker (path/join (os/tmpdir) "penpot-rce-probe")
          out    (await (shell/run-cmd "echo" (str "#000000$(touch " marker ")")))]
      (t/is (str/includes? (str out) "$(touch ")
            "the metacharacters traveled, they did not run")
      (t/is (not (fs/existsSync marker))
            "no RCE: the marker file was NOT created"))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

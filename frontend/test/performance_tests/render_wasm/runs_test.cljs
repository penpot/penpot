;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns performance-tests.render-wasm.runs-test
  (:require
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   [benches.render-wasm.node.artifacts :as artifacts]
   [benches.render-wasm.node.runs :as runs]
   [benches.render-wasm.result :as result]
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]))

(def ^:private temporary-root (atom nil))

(t/use-fixtures :each
  {:before #(reset! temporary-root
                    (fs/mkdtempSync (path/join (os/tmpdir) "penpot-render-runs-")))
   :after #(fs/rmSync @temporary-root #js {:recursive true :force true})})

(defn- failure
  [f]
  (try
    (f)
    nil
    (catch :default error error)))

(defn- sample-run
  [run-id]
  (result/create-run
   {:run-id run-id
    :started-at "2026-10-26T00:00:00.000Z"
    :plan {:cases [{:id :rects/load}]
           :warmups 0
           :repetitions 1
           :timeout-policy {:attempt-ms 30000}}
    :metadata {:git {:sha "abc" :dirty false}
               :build {:functional {:mode :release}}
               :environment {:node "v24.0.0"}
               :scored? true}}))

(t/deftest managed-home-lives-under-the-frontend-root
  (let [home (runs/create-home {:root @temporary-root :run-id "run-1"})]
    (t/is (= (path/join (artifacts/managed-root @temporary-root) "runs" "run-1")
             (:dir home)))
    (t/is (.isDirectory (fs/statSync (:dir home))))
    (t/is (= "" (fs/readFileSync (:log-path home) "utf8")))
    (t/is (not (fs/existsSync (:record-path home))))))

(t/deftest explicit-home-must-not-exist-and-resolves-from-caller-cwd
  (let [previous (.cwd js/process)
        relative "explicit-run"]
    (try
      (.chdir js/process @temporary-root)
      (let [home (runs/create-home {:root @temporary-root
                                    :run-id "run-2"
                                    :output-dir relative})]
        (t/is (= (path/join @temporary-root relative) (:dir home)))
        (t/is (.isDirectory (fs/statSync (:dir home))))
        (t/is (not (fs/existsSync (path/join (artifacts/managed-root @temporary-root)
                                             "runs" "run-2")))))
      (finally
        (.chdir js/process previous))))
  (let [directory (path/join @temporary-root "already-there")]
    (fs/mkdirSync directory)
    (fs/writeFileSync (path/join directory "sentinel") "keep")
    (let [error (failure #(runs/create-home {:root @temporary-root
                                             :run-id "run-3"
                                             :output-dir directory}))]
      (t/is (= ::runs/invalid-home (:type (ex-data error))))
      (t/is (= "output directory already exists" (ex-message error)))
      (t/is (= "keep" (fs/readFileSync (path/join directory "sentinel") "utf8"))))))

(t/deftest record-writes-are-atomic-and-invalid-records-leave-the-saved-file
  (let [home (runs/create-home {:root @temporary-root :run-id "run-4"})
        run  (sample-run "run-4")]
    (runs/write-record! home run)
    (t/is (= run (result/decode (fs/readFileSync (:record-path home) "utf8"))))
    (t/is (empty? (filter #(str/ends-with? % ".tmp")
                          (array-seq (fs/readdirSync (:dir home))))))
    (let [error (failure #(runs/write-record! home (assoc-in run [:metadata :scored?] "yes")))]
      (t/is (= ::result/invalid-record (:type (ex-data error))))
      (t/is (= run (result/decode (fs/readFileSync (:record-path home) "utf8")))))))

(t/deftest log-and-screenshot-paths-stay-in-the-run-home
  (let [home (runs/create-home {:root @temporary-root :run-id "run-5"})]
    (runs/append-log! home "first\n")
    (runs/append-log! home "second\n")
    (t/is (= "first\nsecond\n" (fs/readFileSync (:log-path home) "utf8")))
    (let [shot (runs/screenshot-path home :rects/load)]
      (t/is (= "rects-load.png" (:file shot)))
      (t/is (= (path/join (:dir home) "rects-load.png") (:path shot)))
      (t/is (= (path/dirname (:record-path home)) (path/dirname (:path shot)))))))

(t/deftest malformed-ids-and-empty-paths-fail-at-the-home-boundary
  (doseq [id ["../escape" "" "a/b" "a\\b"]]
    (t/is (thrown-with-msg? js/Error #"invalid run id"
                            (runs/create-home {:root @temporary-root :run-id id}))))
  (t/is (thrown-with-msg? js/Error #"nonempty path"
                          (runs/check-output-dir "  "))))

(t/deftest explicit-home-creates-missing-parents
  (let [out  (path/join @temporary-root "missing" "parents" "run")
        home (runs/create-home {:root @temporary-root :run-id "nested"
                                :output-dir out})]
    (t/is (.isDirectory (fs/statSync out)))
    (t/is (= "" (fs/readFileSync (:log-path home) "utf8")))
    (t/is (thrown-with-msg? js/Error #"already exists"
                            (runs/create-home {:root @temporary-root :run-id "again"
                                               :output-dir out})))))

(t/deftest remove-rejects-output-inside-builds-before-creating-it
  (let [builds (path/join (artifacts/managed-root @temporary-root) "builds")
        alias  (path/join @temporary-root "build-alias")]
    (fs/mkdirSync builds #js {:recursive true})
    (fs/symlinkSync builds alias "dir")
    (doseq [out [(path/join builds "direct")
                 (path/join alias "missing" "nested")]]
      (t/is (thrown-with-msg? js/Error #"--remove.*inside builds"
                              (runs/create-home {:root @temporary-root :run-id "blocked"
                                                 :output-dir out :remove true})))
      (t/is (not (fs/existsSync out))))
    (let [out (path/join @temporary-root "builds-other" "allowed")]
      (runs/create-home {:root @temporary-root :run-id "allowed"
                         :output-dir out :remove true})
      (t/is (fs/existsSync (path/join out "run.log"))))))

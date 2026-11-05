;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.exporter-tmpdir-test
  "The temp area owns what jobs park in it: every path a job tracks is
  removed on its release, and the boot clean drops whatever a previous
  process left behind."
  (:require
   ["node:fs" :as fs]
   ["node:fs/promises" :as fsp]
   ["node:os" :as os]
   ["node:path" :as path]
   [app.common.uuid :as uuid]
   [cljs.test :as t :include-macros true]
   [exporter.tmpdir :as tmpdir]
   [exporter.utils.system :as system]))

(defn- test-area
  []
  (path/join (os/tmpdir) (str "penpot-tmpdir-test." (uuid/next))))

(defn- ^:async write
  [dir name content]
  (let [p (path/join dir name)]
    (await (fsp/writeFile p content))
    p))

(t/deftest ^:async track-registers-and-release-removes
  (let [dir    (test-area)
        _      (fs/mkdirSync dir #js {:recursive true})
        job-id (uuid/next)
        owned  (tmpdir/track job-id (await (write dir "penpot.a.txt" "a")))
        _      (tmpdir/track job-id (await (write dir "penpot.b.txt" "b")))]
    (try
      (t/is (= owned (path/join dir "penpot.a.txt")))
      (await (tmpdir/release job-id))
      (t/is (nil? (try (await (fsp/stat owned)) (catch :default _ nil)))
            "tracked paths are gone after the release")
      (t/testing "releasing twice, or an unknown job, is a no-op"
        (await (tmpdir/release job-id))
        (await (tmpdir/release (uuid/next))))
      (finally
        (await (fsp/rm dir #js {:recursive true :force true}))))))

(t/deftest ^:async clean-orphans-removes-only-aged-managed-files
  (let [dir  (test-area)
        _    (fs/mkdirSync dir #js {:recursive true})
        old  (await (write dir "penpot.old.txt" "old"))
        fresh (await (write dir "penpot.fresh.txt" "fresh"))
        alien (await (write dir "other.txt" "other"))]
    (try
      (.utimesSync fs old (js/Date.) (js/Date. (- (js/Date.now) 7200000)))
      (t/is (= 1 (await (tmpdir/clean-orphans dir 3600))))
      (t/testing "the aged managed file is gone"
        (t/is (nil? (try (await (fsp/stat old)) (catch :default _ nil)))))
      (t/testing "fresh managed and unmanaged files stay"
        (t/is (some? (await (fsp/stat fresh))))
        (t/is (some? (await (fsp/stat alien)))))
      (finally
        (await (fsp/rm dir #js {:recursive true :force true}))))))

(t/deftest ^:async init-ensures-the-dir-and-cleans-orphans
  (let [dir (test-area)]
    (try
      (t/is (= dir (await (system/init-key :exporter/tmpdir {:path dir :job-ttl 3600}))))
      (t/is (some? (await (fsp/stat dir))))
      (t/testing "a missing ttl fails fast instead of cleaning with a default"
        (let [cause (try
                      (await (system/init-key :exporter/tmpdir {:path dir}))
                      nil
                      (catch :default cause
                        cause))]
          (t/is (= :job-ttl-missing (:code (ex-data cause))))))
      (finally
        (await (fsp/rm dir #js {:recursive true :force true}))))))

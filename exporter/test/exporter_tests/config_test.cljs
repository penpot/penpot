;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.config-test
  (:require
   ["node:fs" :as fs]
   ["node:os" :as os]
   ["node:path" :as path]
   [app.config :as cf]
   [cljs.test :as t :include-macros true]))

(defn- with-key-file
  [content f]
  (let [dir  (os/tmpdir)
        file (path/join dir (str "penpot-exporter-key-" (random-uuid) ".txt"))]
    (fs/writeFileSync file content)
    (try
      (f file)
      (finally
        (when (fs/existsSync file)
          (fs/unlinkSync file))))))

(defn- prepare-with-env
  [env]
  (with-redefs [cf/read-env (constantly env)]
    (cf/prepare-config)))

(t/deftest explicit-shared-key-without-master-is-valid
  (let [cfg (prepare-with-env {:exporter-shared-key "explicit"})]
    (t/is (= "explicit" (:exporter-shared-key cfg)))
    (t/is (nil? (:secret-key cfg)))
    (t/is (= "explicit" (cf/get-management-key cfg)))))

(t/deftest key-file-is-read-and-trimmed
  (with-key-file "from-file\n"
    (fn [file]
      (let [cfg (prepare-with-env {:exporter-shared-key-file file})]
        (t/is (= "from-file" (:exporter-shared-key cfg)))
        (t/is (= "from-file" (cf/get-management-key cfg)))))))

(t/deftest direct-env-key-wins-over-file
  (with-key-file "from-file"
    (fn [_file]
      (let [cfg (prepare-with-env {:exporter-shared-key "direct"
                                   :exporter-shared-key-file
                                   "/tmp/opencode/penpot-key-does-not-exist"})]
        (t/is (= "direct" (:exporter-shared-key cfg)))))))

(t/deftest missing-key-file-throws
  (let [err (try
              (prepare-with-env {:exporter-shared-key-file
                                 "/tmp/opencode/penpot-key-does-not-exist"})
              nil
              (catch :default e e))]
    (t/is (some? err))))

(t/deftest empty-key-file-throws
  (with-key-file "  \n\t"
    (fn [file]
      (let [err (try
                  (prepare-with-env {:exporter-shared-key-file file})
                  nil
                  (catch :default e e))]
        (t/is (some? err))))))

(t/deftest no-key-source-is-rejected
  (t/testing "neither a master secret nor an explicit key is a valid source"
    (t/is (false? (cf/has-key-source? {})))
    (t/is (true? (cf/has-key-source? {:secret-key "master"})))
    (t/is (true? (cf/has-key-source? {:exporter-shared-key "explicit"})))))

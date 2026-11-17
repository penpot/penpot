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
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]))

(defn- with-temp-dir
  [f]
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "penpot-exporter-key-"))]
    (try
      (f dir)
      (finally (fs/rmSync dir #js {:recursive true :force true})))))

(defn- write-key!
  [dir content]
  (let [file (path/join dir "key")]
    (fs/writeFileSync file content)
    file))

(defn- error-of
  [f]
  (try
    (f)
    nil
    (catch :default e e)))

(defn- message
  [err]
  (.-message err))

(t/deftest explicit-shared-key-without-master-is-valid
  (let [cfg (cf/prepare-config {:exporter-shared-key "explicit"})]
    (t/is (= "explicit" (:exporter-shared-key cfg)))
    (t/is (nil? (:secret-key cfg)))
    (t/is (= "explicit" (cf/get-management-key cfg)))))

(t/deftest key-file-is-read-trimmed-and-unwrapped
  (with-temp-dir
    (fn [dir]
      (let [file (write-key! dir "from-\n file \tkey")
            cfg  (cf/prepare-config {:exporter-shared-key-file file})]
        (t/is (= "from-filekey" (:exporter-shared-key cfg)))
        (t/is (= "from-filekey" (cf/get-management-key cfg)))))))

(t/deftest key-file-beats-derivation
  (with-temp-dir
    (fn [dir]
      (let [file (write-key! dir "from-file")
            cfg  (cf/prepare-config {:secret-key "master"
                                     :exporter-shared-key-file file})]
        (t/is (= "from-file" (:exporter-shared-key cfg)))))))

(t/deftest direct-env-key-wins-over-file
  (with-temp-dir
    (fn [dir]
      (let [cfg (cf/prepare-config {:exporter-shared-key "direct"
                                    :exporter-shared-key-file
                                    (path/join dir "missing")})]
        (t/is (= "direct" (:exporter-shared-key cfg)))))))

(t/deftest missing-key-file-names-the-variable
  (with-temp-dir
    (fn [dir]
      (let [err (error-of #(cf/prepare-config {:exporter-shared-key-file
                                               (path/join dir "missing")}))]
        (t/is (some? err))
        (t/is (str/includes? (message err) "PENPOT_EXPORTER_SHARED_KEY_FILE"))
        (t/is (str/includes? (message err) "missing or empty"))))))

(t/deftest empty-key-file-is-rejected
  (with-temp-dir
    (fn [dir]
      (let [err (error-of #(cf/prepare-config {:exporter-shared-key-file
                                               (write-key! dir "  \n\t")}))]
        (t/is (some? err))
        (t/is (str/includes? (message err) "PENPOT_EXPORTER_SHARED_KEY_FILE"))
        (t/is (str/includes? (message err) "missing or empty"))))))

(t/deftest directory-as-key-file-is-rejected
  (with-temp-dir
    (fn [dir]
      (let [err (error-of #(cf/prepare-config {:exporter-shared-key-file dir}))]
        (t/is (some? err))
        (t/is (str/includes? (message err) "missing or empty"))))))

(t/deftest oversized-key-file-is-rejected
  (with-temp-dir
    (fn [dir]
      (let [err (error-of #(cf/prepare-config {:exporter-shared-key-file
                                               (write-key! dir (apply str (repeat 5000 "a")))}))]
        (t/is (some? err))
        (t/is (str/includes? (message err) "too large"))))))

(t/deftest blank-explicit-key-disables-the-exporter
  (let [err (error-of #(cf/prepare-config {:exporter-shared-key ""}))]
    (t/is (some? err))
    (t/is (str/includes? (message err) "Either PENPOT_EXPORTER_SHARED_KEY"))))

(t/deftest get-management-key-without-a-source-is-rejected
  (let [err (error-of #(cf/get-management-key {}))]
    (t/is (some? err))
    (t/is (str/includes? (message err) "PENPOT_SECRET_KEY"))))

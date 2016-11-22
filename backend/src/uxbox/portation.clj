;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.portation
  "Support for export/import operations of projects."
  (:refer-clojure :exclude [with-open])
  (:require [clojure.java.io :as io]
            [suricatta.core :as sc]
            [storages.fs :as fs]
            [uxbox.db :as db]
            [uxbox.sql :as sql]
            [uxbox.util.uuid :as uuid]
            [uxbox.util.closeable :refer (with-open)]
            [uxbox.util.transit :as t]
            [uxbox.util.snappy :as snappy]))

;; --- Export

(defn- write-project
  [conn writer id]
  (let [sql (sql/get-project-by-id {:id id})
        result (sc/fetch-one conn sql)]
    (when-not result
      (ex-info "No project found with specified id" {:id id}))
    (t/write! writer {::type ::project ::payload result})))

(defn- write-pages
  [conn writer id]
  (let [sql (sql/get-pages-for-project {:project id})
        results (sc/fetch conn sql)]
    (run! #(t/write! writer {::type ::page ::payload %}) results)))

(defn- write-pages-history
  [conn writer id]
  (let [sql (sql/get-page-history-for-project {:project id})
        results (sc/fetch conn sql)]
    (run! #(t/write! writer {::type ::page-history ::payload %}) results)))

(defn- write-data
  [path id]
  (with-open [ostream (io/output-stream path)
              zstream (snappy/output-stream ostream)
              conn (db/connection)]
    (let [writer (t/writer zstream {:type :msgpack})]
      (sc/atomic conn
        (write-project conn writer id)
        (write-pages conn writer id)
        (write-pages-history conn writer id)))))

(defn export
  "Given an id, returns a path to a temporal file with the exported
  bundle of the specified project."
  [id]
  (let [path (fs/create-tempfile)]
    (write-data path id)
    path))

;; --- Import

(defn- read-entry
  [reader]
  (try
    (t/read! reader)
    (catch RuntimeException e
      (let [cause (.getCause e)]
        (if (instance? java.io.EOFException cause)
          ::eof
         (throw e))))))

(defn- persist-project
  [conn project]
  (let [sql (sql/create-project project)]
    (sc/execute conn sql)))

(defn- persist-page
  [conn page]
  (let [sql (sql/create-page page)]
    (sc/execute conn sql)))

(defn- persist-page-history
  [conn history]
  (let [sql (sql/create-page-history history)]
    (sc/execute conn sql)))

(defn- persist-entry
  [conn entry]
  (let [payload (::payload entry)
        type (::type entry)]
    (case type
      ::project (persist-project conn payload)
      ::page (persist-page conn payload)
      ::page-history (persist-page-history conn payload))))

(defn- read-data
  [conn reader]
  (loop [entry (read-entry reader)]
    (when (not= entry ::eof)
      (persist-entry conn entry)
      (recur (read-entry reader)))))

(defn import!
  "Given a path to the previously exported bundle, try to import it."
  [path]
  (with-open [istream (io/input-stream (fs/path path))
              zstream (snappy/input-stream istream)
              conn (db/connection)]
    (let [reader (t/reader zstream {:type :msgpack})]
      (sc/atomic conn
        (read-data conn reader)
        nil))))

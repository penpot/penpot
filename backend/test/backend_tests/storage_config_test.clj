;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.storage-config-test
  (:require
   [app.common.uri :as u]
   [app.config :as cf]
   [app.storage.config :as sto.config]
   [clojure.java.io :as io]
   [clojure.test :as t]))

(defn- write-routes!
  [content]
  (let [file (java.io.File/createTempFile "storage-routes" ".edn")]
    (spit file content)
    file))

(defn- delete!
  [file]
  (io/delete-file file true))

(defn- load-with
  [config]
  (binding [cf/config (merge cf/config
                             {:objects-storage-backend :s3}
                             config)]
    (sto.config/load)))

(defn- load-error
  "Runs `load` against a routes file with `content` and returns the raised
  throwable, or nil when it succeeds."
  [content config]
  (let [file (write-routes! content)]
    (try
      (try
        (load-with (assoc config :objects-storage-s3-routes-file (.getAbsolutePath file)))
        nil
        (catch Throwable cause cause))
      (finally
        (delete! file)))))

(t/deftest returns-empty-when-unset
  (t/is (= {:targets nil :routes nil}
           (load-with {:objects-storage-s3-routes-file nil}))))

(t/deftest loads-and-normalizes-routes
  (let [file (write-routes!
              "{:targets {:temp {:bucket \"penpot-temp\" :endpoint \"https://s3.example.com\"}} :routes {\"tempfile\" :temp}}")]
    (try
      (let [result (load-with {:objects-storage-s3-routes-file (.getAbsolutePath file)})]
        (t/is (= #{:temp} (set (keys (:targets result)))))
        (t/is (= "penpot-temp" (get-in result [:targets :temp :bucket])))
        (t/is (u/uri? (get-in result [:targets :temp :endpoint])))
        (t/is (= {"tempfile" :temp} (:routes result))))
      (finally
        (delete! file)))))

(t/deftest rejects-reserved-default-target
  (t/is (some? (load-error "{:targets {:default {:bucket \"x\"}}}"
                           {}))))

(t/deftest rejects-unknown-route-target
  (t/is (some? (load-error
                "{:targets {:temp {:bucket \"x\"}} :routes {\"tempfile\" :missing}}"
                {}))))

(t/deftest rejects-invalid-semantic-bucket
  (t/is (some? (load-error
                "{:targets {:temp {:bucket \"x\"}} :routes {\"not-a-bucket\" :temp}}"
                {}))))

(t/deftest rejects-unreadable-file
  (t/is (some? (load-error "{:targets" {}))))

(t/deftest rejects-routing-when-backend-is-not-s3
  (t/is (some? (load-error
                "{:targets {:temp {:bucket \"x\"}} :routes {\"tempfile\" :temp}}"
                {:objects-storage-backend :fs}))))

(t/deftest rejects-route-pointing-to-default
  (let [err (load-error
             "{:targets {:temp {:bucket \"x\"}} :routes {\"tempfile\" :default}}"
             {})]
    (t/is (some? err))
    (t/is (= :unknown-storage-target (:code (ex-data err))))))

(t/deftest loads-empty-targets-map
  (let [file (write-routes! "{:targets {}}")]
    (try
      (let [result (load-with {:objects-storage-s3-routes-file (.getAbsolutePath file)})]
        (t/is (= {} (:targets result)))
        (t/is (nil? (:routes result))))
      (finally
        (delete! file)))))

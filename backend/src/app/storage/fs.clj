;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.storage.fs
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.db :as db]
   [app.storage.impl :as impl]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [datoteka.core :as fs]
   [lambdaisland.uri :as u]
   [integrant.core :as ig])
  (:import
   java.io.InputStream
   java.io.OutputStream
   java.nio.file.Path
   java.nio.file.Files))

;; --- BACKEND INIT

(s/def ::directory ::us/string)
(s/def ::uri ::us/string)

(defmethod ig/pre-init-spec ::backend [_]
  (s/keys :opt-un [::directory ::uri]))

(defmethod ig/init-key ::backend
  [_ cfg]
  ;; Return a valid backend data structure only if all optional
  ;; parameters are provided.
  (when (and (string? (:directory cfg))
             (string? (:uri cfg)))
    (assoc cfg :type :fs)))

(s/def ::type #{:fs})
(s/def ::backend
  (s/keys :req-un [::directory ::uri ::type]))

;; --- API IMPL

(defmethod impl/put-object :fs
  [backend {:keys [id] :as object} content]
  (let [^Path base (fs/path (:directory backend))
        ^Path path (fs/path (impl/id->path id))
        ^Path full (.resolve base path)]
    (when-not (fs/exists? (.getParent full))
      (fs/create-dir (.getParent full)))
    (with-open [^InputStream src  (io/input-stream content)
                ^OutputStream dst (io/output-stream full)]
      (io/copy src dst))))

(defmethod impl/get-object :fs
  [backend {:keys [id] :as object}]
  (let [^Path base (fs/path (:directory backend))
        ^Path path (fs/path (impl/id->path id))
        ^Path full (.resolve base path)]
    (when-not (fs/exists? full)
      (ex/raise :type :internal
                :code :filesystem-object-does-not-exists
                :path (str full)))
    (io/input-stream full)))

(defmethod impl/get-object-url :fs
  [backend {:keys [id] :as object} _]
  (let [uri (u/uri (:uri backend))]
    (update uri :path
            (fn [existing]
              (str existing (impl/id->path id))))))

(defmethod impl/del-objects-in-bulk :fs
  [backend ids]
  (let [base (fs/path (:directory backend))]
    (doseq [id ids]
      (let [path (fs/path (impl/id->path id))
            path (.resolve ^Path base ^Path path)]
        (Files/deleteIfExists ^Path path)))))

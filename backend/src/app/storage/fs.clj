;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.storage.fs
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uri :as u]
   [app.storage.impl :as impl]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [datoteka.core :as fs]
   [integrant.core :as ig])
  (:import
   java.io.InputStream
   java.io.OutputStream
   java.nio.file.Path
   java.nio.file.Files))

;; --- BACKEND INIT

(s/def ::directory ::us/string)

(defmethod ig/pre-init-spec ::backend [_]
  (s/keys :opt-un [::directory]))

(defmethod ig/init-key ::backend
  [_ cfg]
  ;; Return a valid backend data structure only if all optional
  ;; parameters are provided.
  (when (string? (:directory cfg))
    (let [dir (fs/normalize (:directory cfg))]
      (assoc cfg
             :type :fs
             :directory (str dir)
             :uri (u/uri (str "file://" dir))))))

(s/def ::type ::us/keyword)
(s/def ::uri u/uri?)
(s/def ::backend
  (s/keys :req-un [::type ::directory ::uri]))

;; --- API IMPL

(defmethod impl/put-object :fs
  [backend {:keys [id] :as object} content]
  (let [base (fs/path (:directory backend))
        path (fs/path (impl/id->path id))
        full (fs/normalize (fs/join base path))]
    (when-not (fs/exists? (fs/parent full))
      (fs/create-dir (fs/parent full)))
    (with-open [^InputStream src  (io/input-stream content)
                ^OutputStream dst (io/output-stream full)]
      (io/copy src dst))))

(defmethod impl/copy-object :fs
  [backend src-object dst-object]
  (let [base (fs/path (:directory backend))
        path (fs/path (impl/id->path (:id dst-object)))
        full (fs/normalize (fs/join base path))]
    (when-not (fs/exists? (fs/parent full))
      (fs/create-dir (fs/parent full)))
    (with-open [^InputStream src  (impl/get-object-data backend src-object)
                ^OutputStream dst (io/output-stream full)]
      (io/copy src dst))))

(defmethod impl/get-object-data :fs
  [backend {:keys [id] :as object}]
  (let [^Path base (fs/path (:directory backend))
        ^Path path (fs/path (impl/id->path id))
        ^Path full (fs/normalize (fs/join base path))]
    (when-not (fs/exists? full)
      (ex/raise :type :internal
                :code :filesystem-object-does-not-exists
                :path (str full)))
    (io/input-stream full)))

(defmethod impl/get-object-url :fs
  [{:keys [uri] :as backend} {:keys [id] :as object} _]
  (update uri :path
          (fn [existing]
            (if (str/ends-with? existing "/")
              (str existing (impl/id->path id))
              (str existing "/" (impl/id->path id))))))

(defmethod impl/del-objects-in-bulk :fs
  [backend ids]
  (let [base (fs/path (:directory backend))]
    (doseq [id ids]
      (let [path (fs/path (impl/id->path id))
            path (fs/join base path)]
        (Files/deleteIfExists ^Path path)))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.storage.fs
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.uri :as u]
   [app.storage :as-alias sto]
   [app.storage.impl :as impl]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [datoteka.io :as io]
   [integrant.core :as ig])
  (:import
   java.io.InputStream
   java.io.OutputStream
   java.nio.file.Files
   java.nio.file.Path))

(set! *warn-on-reflection* true)

;; --- BACKEND INIT

(defmethod ig/assert-key ::backend
  [_ params]
  ;; FIXME:  path (?)
  (assert (string? (::directory params))))

(defmethod ig/init-key ::backend
  [_ cfg]
  ;; Return a valid backend data structure only if all optional
  ;; parameters are provided.
  (when (string? (::directory cfg))
    (let [dir (fs/normalize (::directory cfg))]
      (assoc cfg
             ::sto/type :fs
             ::directory (str dir)
             ::uri (u/uri (str "file://" dir))))))

(def ^:private schema:backend
  [:map {:title "fs-backend"}
   [::directory :string]
   [::uri ::sm/uri]
   [::sto/type [:= :fs]]])

(sm/register! ::backend schema:backend)

(def ^:private valid-backend?
  (sm/validator schema:backend))

;; --- API IMPL

(defmethod impl/put-object :fs
  [backend {:keys [id] :as object} content]
  (assert (valid-backend? backend) "expected a valid backend instance")
  (let [base (fs/path (::directory backend))
        path (fs/path (impl/id->path id))
        full (fs/normalize (fs/join base path))]

    (when-not (fs/exists? (fs/parent full))
      (fs/create-dir (fs/parent full)))

    (with-open [^InputStream src (io/input-stream content)]
      (with-open [^OutputStream dst (io/output-stream full)]
        (io/copy src dst)))

    object))

(defmethod impl/get-object-data :fs
  [backend {:keys [id] :as object}]
  (assert (valid-backend? backend) "expected a valid backend instance")
  (let [^Path base (fs/path (::directory backend))
        ^Path path (fs/path (impl/id->path id))
        ^Path full (fs/normalize (fs/join base path))]
    (when-not (fs/exists? full)
      (ex/raise :type :internal
                :code :filesystem-object-does-not-exists
                :path (str full)))
    (io/input-stream full)))

(defmethod impl/get-object-bytes :fs
  [backend object]
  (with-open [^InputStream input (impl/get-object-data backend object)]
    (io/read input)))

(defmethod impl/get-object-url :fs
  [{:keys [::uri] :as backend} {:keys [id] :as object} _]
  (assert (valid-backend? backend) "expected a valid backend instance")
  (update uri :path
          (fn [existing]
            (if (str/ends-with? existing "/")
              (str existing (impl/id->path id))
              (str existing "/" (impl/id->path id))))))

(defmethod impl/del-object :fs
  [backend {:keys [id] :as object}]
  (assert (valid-backend? backend) "expected a valid backend instance")
  (let [base (fs/path (::directory backend))
        path (fs/path (impl/id->path id))
        path (fs/join base path)]
    (Files/deleteIfExists ^Path path)))

(defmethod impl/del-objects-in-bulk :fs
  [backend ids]
  (assert (valid-backend? backend) "expected a valid backend instance")
  (let [base (fs/path (::directory backend))]
    (doseq [id ids]
      (let [path (fs/path (impl/id->path id))
            path (fs/join base path)]
        (Files/deleteIfExists ^Path path)))))


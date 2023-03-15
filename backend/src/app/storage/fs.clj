;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.storage.fs
  (:require
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uri :as u]
   [app.storage :as-alias sto]
   [app.storage.impl :as impl]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [datoteka.io :as io]
   [integrant.core :as ig])
  (:import
   java.nio.file.Path
   java.nio.file.Files))

;; --- BACKEND INIT

(s/def ::directory ::us/string)

(defmethod ig/pre-init-spec ::backend [_]
  (s/keys :opt [::directory]))

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

(s/def ::uri u/uri?)
(s/def ::backend
  (s/keys :req [::directory
                ::uri]
          :opt [::sto/type
                ::sto/id]))

;; --- API IMPL

(defmethod impl/put-object :fs
  [backend {:keys [id] :as object} content]
  (us/assert! ::backend backend)
  (let [base (fs/path (::directory backend))
        path (fs/path (impl/id->path id))
        full (fs/normalize (fs/join base path))]

    (when-not (fs/exists? (fs/parent full))
      (fs/create-dir (fs/parent full)))

    (dm/with-open [src (io/input-stream content)
                   dst (io/output-stream full)]
      (io/copy! src dst))

    object))

(defmethod impl/get-object-data :fs
  [backend {:keys [id] :as object}]
  (us/assert! ::backend backend)
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
  (dm/with-open [input (impl/get-object-data backend object)]
    (io/read-as-bytes input)))

(defmethod impl/get-object-url :fs
  [{:keys [::uri] :as backend} {:keys [id] :as object} _]
  (us/assert! ::backend backend)
  (update uri :path
          (fn [existing]
            (if (str/ends-with? existing "/")
              (str existing (impl/id->path id))
              (str existing "/" (impl/id->path id))))))

(defmethod impl/del-object :fs
  [backend {:keys [id] :as object}]
  (us/assert! ::backend backend)
  (let [base (fs/path (::directory backend))
        path (fs/path (impl/id->path id))
        path (fs/join base path)]
    (Files/deleteIfExists ^Path path)))

(defmethod impl/del-objects-in-bulk :fs
  [backend ids]
  (us/assert! ::backend backend)
  (let [base (fs/path (::directory backend))]
    (doseq [id ids]
      (let [path (fs/path (impl/id->path id))
            path (fs/join base path)]
        (Files/deleteIfExists ^Path path)))))


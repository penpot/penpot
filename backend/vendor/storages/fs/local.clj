;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns storages.fs.local
  "A local filesystem storage implementation."
  (:require [promesa.core :as p]
            [clojure.java.io :as io]
            [executors.core :as exec]
            [storages.proto :as pt]
            [storages.impl :as impl]
            [storages.util :as util])
  (:import java.io.InputStream
           java.io.OutputStream
           java.net.URI
           java.nio.file.Path
           java.nio.file.Files))

(defn normalize-path
  [^Path base ^Path path]
  (if (util/absolute? path)
    (throw (ex-info "Suspicios operation: absolute path not allowed."
                    {:path (str path)}))
    (let [^Path fullpath (.resolve base path)
          ^Path fullpath (.normalize fullpath)]
      (when-not (.startsWith fullpath base)
        (throw (ex-info "Suspicios operation: go to parent dir is not allowed."
                        {:path (str path)})))
      fullpath)))

(defn- save
  [base path content]
  (let [^Path path (pt/-path path)
        ^Path fullpath (normalize-path base path)]
    (when-not (util/exists? (.getParent fullpath))
      (util/create-dir! (.getParent fullpath)))
    (with-open [^InputStream source (pt/-input-stream content)
                ^OutputStream dest (Files/newOutputStream
                                    fullpath util/write-open-opts)]
      (io/copy source dest)
      path)))

(defn- delete
  [base path]
  (let [path (->> (pt/-path path)
                  (normalize-path base))]
    (Files/deleteIfExists ^Path path)))

(defrecord FileSystemStorage [^Path base ^URI baseuri]
  pt/IPublicStorage
  (-public-uri [_ path]
    (.resolve baseuri (str path)))

  pt/IStorage
  (-save [_ path content]
    (exec/submit (partial save base path content)))

  (-delete [_ path]
    (exec/submit (partial delete base path)))

  (-exists? [this path]
    (try
      (p/resolved
       (let [path (->> (pt/-path path)
                       (normalize-path base))]
         (util/exists? path)))
      (catch Exception e
        (p/rejected e))))

  pt/IClearableStorage
  (-clear [_]
    (util/delete-dir! base)
    (util/create-dir! base))

  pt/ILocalStorage
  (-lookup [_ path']
    (try
      (p/resolved
       (->> (pt/-path path')
            (normalize-path base)))
      (catch Exception e
        (p/rejected e)))))

(defn filesystem
  "Create an instance of local FileSystem storage providing an
  absolute base path.

  If that path does not exists it will be automatically created,
  if it exists but is not a directory, an exception will be
  raised."
  [{:keys [basedir baseuri] :as keys}]
  (let [^Path basepath (pt/-path basedir)
        ^URI baseuri (pt/-uri baseuri)]
    (when (and (util/exists? basepath)
               (not (util/directory? basepath)))
      (throw (ex-info "File already exists." {})))

    (when-not (util/exists? basepath)
      (util/create-dir! basepath))

    (->FileSystemStorage basepath baseuri)))


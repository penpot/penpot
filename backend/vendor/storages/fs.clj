;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns storages.fs
  "File System helpers."
  (:refer-clojure :exclude [name resolve])
  (:require [storages.proto :as pt])
  (:import java.nio.file.Path
           java.nio.file.Files
           java.nio.file.LinkOption
           java.nio.file.OpenOption
           java.nio.file.CopyOption
           java.nio.file.StandardOpenOption
           java.nio.file.StandardCopyOption
           java.nio.file.SimpleFileVisitor
           java.nio.file.FileVisitResult
           java.nio.file.attribute.FileAttribute
           java.nio.file.attribute.PosixFilePermissions
           ratpack.form.UploadedFile))

;; --- Constants

(def write-open-opts
  (->> [StandardOpenOption/TRUNCATE_EXISTING
        StandardOpenOption/CREATE
        StandardOpenOption/WRITE]
       (into-array OpenOption)))

(def read-open-opts
  (->> [StandardOpenOption/READ]
       (into-array OpenOption)))

(def move-opts
  (->> [StandardCopyOption/ATOMIC_MOVE
        StandardCopyOption/REPLACE_EXISTING]
       (into-array CopyOption)))

(def follow-link-opts
  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

;; --- Path Helpers

(defn path
  "Create path from string or more than one string."
  ([fst]
   (pt/-path fst))
  ([fst & more]
   (pt/-path (cons fst more))))

(defn make-file-attrs
  "Generate a array of `FileAttribute` instances
  generated from `rwxr-xr-x` kind of expressions."
  [^String expr]
  (let [perms (PosixFilePermissions/fromString expr)
        attr (PosixFilePermissions/asFileAttribute perms)]
    (into-array FileAttribute [attr])))

(defn path?
  "Return `true` if provided value is an instance of Path."
  [v]
  (instance? Path v))

(defn absolute?
  "Return `true` if the provided path is absolute, `else` in case contrary.
  The `path` parameter can be anything convertible to path instance."
  [path]
  (let [^Path path (pt/-path path)]
    (.isAbsolute path)))

(defn exists?
  "Return `true` if the provided path exists, `else` in case contrary.
  The `path` parameter can be anything convertible to path instance."
  [path]
  (let [^Path path (pt/-path path)]
    (Files/exists path follow-link-opts)))

(defn directory?
  "Return `true` if the provided path is a directory, `else` in case contrary.
  The `path` parameter can be anything convertible to path instance."
  [path]
  (let [^Path path (pt/-path path)]
    (Files/isDirectory path follow-link-opts)))

(defn parent
  "Get parent path if it exists."
  [path]
  (.getParent ^Path (pt/-path path)))

(defn base-name
  "Get the file name."
  [path]
  (if (instance? UploadedFile path)
    (.getFileName ^UploadedFile path)
    (str (.getFileName ^Path (pt/-path path)))))

(defn split-ext
  "Returns a vector of `[name extension]`."
  [path]
  (let [base (base-name path)
        i (.lastIndexOf base ".")]
    (if (pos? i)
      [(subs base 0 i) (subs base i)]
      [base nil])))

(defn extension
  "Return the extension part of a file."
  [path]
  (last (split-ext path)))

(defn name
  "Return the name part of a file."
  [path]
  (first (split-ext path)))

(defn resolve
  "Resolve path on top of an other path."
  [base path]
  (let [^Path base (pt/-path base)
        ^Path path (pt/-path path)]
    (-> (.resolve base path)
        (.normalize))))

(defn list-directory
  [path]
  (let [path (pt/-path path)]
    (with-open [stream (Files/newDirectoryStream path)]
      (vec stream))))

(defn list-files
  [path]
  (filter (complement directory?) (list-directory path)))

;; --- Side-Effectfull Operations

(defn create-dir!
  "Create a new directory."
  [path]
  (let [^Path path (pt/-path path)
        attrs (make-file-attrs "rwxr-xr-x")]
    (Files/createDirectories path attrs)))

(defn create-dir!
  "Create a new directory."
  [path]
  (let [^Path path (pt/-path path)
        attrs (make-file-attrs "rwxr-xr-x")]
    (Files/createDirectories path attrs)))

(defn delete-dir!
  [path]
  (let [path (pt/-path path)
        visitor (proxy [SimpleFileVisitor] []
                  (visitFile [file attrs]
                    (Files/delete file)
                    FileVisitResult/CONTINUE)
                  (postVisitDirectory [dir exc]
                    (Files/delete dir)
                    FileVisitResult/CONTINUE))]
    (Files/walkFileTree path visitor)))

(defn create-tempfile
  "Create a temporal file."
  [& {:keys [suffix prefix]}]
  (->> (make-file-attrs "rwxr-xr-x")
       (Files/createTempFile prefix suffix)))

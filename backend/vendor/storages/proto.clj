;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns storages.proto
  "A storage abstraction definition.")

(defprotocol IUri
  (-uri [_] "Coerce to uri."))

(defprotocol IPath
  (-path [_] "Coerce to path."))

(defprotocol IContent
  (-input-stream [_] "Coerce to input stream."))

(defprotocol IStorage
  "A basic abstraction for storage access."
  (-save [_ path content] "Persist the content under specified path.")
  (-delete [_ path] "Delete the file by its path.")
  (-exists? [_ path] "Check if file exists by path."))

(defprotocol IClearableStorage
  (-clear [_] "clear all contents of the storage"))

(defprotocol IPublicStorage
  (-public-uri [_ path] "Get a public accessible uri for path."))

(defprotocol ILocalStorage
  (-lookup [_ path] "Resolves the path to the local filesystem."))

(defprotocol IStorageIntrospection
  (-accessed-time [_ path] "Return the last accessed time of the file.")
  (-created-time [_ path] "Return the creation time of the file.")
  (-modified-time [_ path] "Return the last modified time of the file."))


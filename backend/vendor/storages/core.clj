;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns storages.core
  "A storages abstraction layer."
  (:require [storages.proto :as pt]
            [storages.impl]))

(defn save
  "Perists a file or bytes in the storage. This function
  returns a relative path where file is saved.

  The final file path can be different to the one provided
  to this function and the behavior is totally dependen on
  the storage implementation."
  [storage path content]
  (pt/-save storage path content))

(defn lookup
  "Resolve provided relative path in the storage and return
  the local filesystem absolute path to it.
  This method may be not implemented in all storages."
  [storage path]
  {:pre [(satisfies? pt/ILocalStorage storage)]}
  (pt/-lookup storage path))

(defn exists?
  "Check if a  relative `path` exists in the storage."
  [storage path]
  (pt/-exists? storage path))

(defn delete
  "Delete a file from the storage."
  [storage path]
  (pt/-delete storage path))

(defn clear!
  "Clear all contents of the storage."
  [storage]
  (pt/-clear storage))

(defn path
  "Create path from string or more than one string."
  ([fst]
   (pt/-path fst))
  ([fst & more]
   (pt/-path (cons fst more))))

(defn public-url
  [storage path]
  (pt/-public-uri storage path))

(defn storage?
  "Return `true` if `v` implements IStorage protocol"
  [v]
  (satisfies? pt/IStorage v))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.locks
  "Advirsory locks for specific handling concurrent modifications
  on particular objects in the database."
  #_(:require [suricatta.core :as sc])
  (:import clojure.lang.Murmur3))

(defn- uuid->long
  [v]
  (Murmur3/hashUnencodedChars (str v)))

;; (defn acquire!
;;   [conn v]
;;   (let [id (uuid->long v)]
;;     (sc/execute conn ["select pg_advisory_xact_lock(?);" id])
;;     nil))

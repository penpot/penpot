;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.locks
  "A syntactic helpers for using locks."
  (:refer-clojure :exclude [locking])
  (:import
   java.util.concurrent.locks.ReentrantLock
   java.util.concurrent.locks.Lock))

(defn create
  []
  (ReentrantLock.))

(defmacro locking
  [lsym & body]
  (let [lsym (vary-meta lsym assoc :tag `Lock)]
    `(do
       (.lock ~lsym)
       (try
         ~@body
         (finally
           (.unlock ~lsym))))))

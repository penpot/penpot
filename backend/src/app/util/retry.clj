;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.retry
  "A fault tolerance helpers. Allow retry some operations that we know
  we can retry."
  (:require
   [app.common.logging :as l])
  (:import
   org.postgresql.util.PSQLException))

(defn conflict-exception?
  "Check if exception matches a insertion conflict on postgresql."
  [e]
  (and (instance? PSQLException e)
       (= "23505" (.getSQLState ^PSQLException e))))

(defmacro with-retry
  [{:keys [::when ::max-retries ::label] :or {max-retries 3}} & body]
  `(loop [tnum# 1]
     (let [result# (try
                     ~@body
                     (catch Throwable cause#
                       (if (and (~when cause#) (<= tnum# ~max-retries))
                         ::retry
                         (throw cause#))))]
       (if (= ::retry result#)
         (do
           (l/warn :hint "retrying operation" :label ~label :retry tnum#)
           (recur (inc tnum#)))
         result#))))

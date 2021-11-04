;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.retry
  "A fault tolerance helpers. Allow retry some operations that we know
  we can retry."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.util.async :as aa]
   [app.util.services :as sv]))

(defn conflict-db-insert?
  "Check if exception matches a insertion conflict on postgresql."
  [e]
  (and (instance? org.postgresql.util.PSQLException e)
       (= "23505" (.getSQLState e))))

(defn wrap-retry
  [_ f {:keys [::max-retries ::matches ::sv/name]
        :or {max-retries 3
             matches (constantly false)}
        :as mdata}]
  (when (::enabled mdata)
    (l/debug :hint "wrapping retry" :name name))
  (if (::enabled mdata)
    (fn [cfg params]
      (loop [retry 1]
        (when (> retry 1)
          (l/debug :hint "retrying controlled function" :retry retry :name name))
        (let [res (ex/try (f cfg params))]
          (if (ex/exception? res)
            (if (and (matches res) (< retry max-retries))
              (do
                (aa/thread-sleep (* 100 retry))
                (recur (inc retry)))
              (throw res))
            res))))
    f))


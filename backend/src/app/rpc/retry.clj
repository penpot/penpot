;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.retry
  "A fault tolerance RPC middleware. Allow retry some operations that we
  know we can retry."
  (:require
   [app.common.logging :as l]
   [app.util.retry :refer [conflict-exception?]]
   [app.util.services :as sv]))

(defn conflict-db-insert?
  "Check if exception matches a insertion conflict on postgresql."
  [e]
  (conflict-exception? e))

(def always-false (constantly false))

(defn wrap-retry
  [_ f {:keys [::matches ::sv/name] :or {matches always-false} :as mdata}]

  (when (::enabled mdata)
    (l/debug :hint "wrapping retry" :name name))

  (if-let [max-retries (::max-retries mdata)]
    (fn [cfg params]
      ((fn run [retry]
         (try
           (f cfg params)
           (catch Throwable cause
             (if (matches cause)
               (let [current-retry (inc retry)]
                 (l/trace :hint "running retry algorithm" :retry current-retry)
                 (if (<= current-retry max-retries)
                   (run current-retry)
                   (throw cause)))
               (throw cause))))) 1))
    f))


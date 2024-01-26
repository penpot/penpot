;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.retry
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.util.services :as sv])
  (:import
   org.postgresql.util.PSQLException))

(defn conflict-exception?
  "Check if exception matches a insertion conflict on postgresql."
  [e]
  (when-let [cause (ex/instance? PSQLException e)]
    (= "23505" (.getSQLState ^PSQLException cause))))

(def ^:private always-false
  (constantly false))

(defn invoke!
  [{:keys [::max-retries] :or {max-retries 3} :as cfg} f & args]
  (loop [rnum 1]
    (let [match? (get cfg ::when always-false)
          result (try
                   (apply f cfg args)
                   (catch Throwable cause
                     (if (and (match? cause) (<= rnum max-retries))
                       ::retry
                       (throw cause))))]
      (if (= ::retry result)
        (let [label (get cfg ::label "anonymous")]
          (l/warn :hint "retrying operation" :label label :retry rnum)
          (recur (inc rnum)))
        result))))


(defn wrap-retry
  [_ f {:keys [::sv/name] :as mdata}]

  (if (::enabled mdata)
    (let [max-retries (get mdata ::max-retries 3)
          matches?    (get mdata ::when always-false)]
      (l/dbg :hint "wrapping retry" :name name :max-retries max-retries)
      (fn [cfg params]
        (-> cfg
            (assoc ::max-retries max-retries)
            (assoc ::when matches?)
            (assoc ::label name)
            (invoke! f params))))
    f))


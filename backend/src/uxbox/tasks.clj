;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.tasks
  (:require
   [cuerdas.core :as str]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [uxbox.common.spec :as us]
   [uxbox.common.uuid :as uuid]
   [uxbox.db :as db]
   [uxbox.util.time :as dt]
   [uxbox.metrics :as mtx]))

(s/def ::name ::us/string)
(s/def ::delay
  (s/or :int ::us/integer
        :duration dt/duration?))
(s/def ::queue ::us/string)

(s/def ::task-options
  (s/keys :req-un [::name]
          :opt-un [::delay ::props ::queue]))

(def ^:private sql:insert-new-task
  "insert into task (id, name, props, queue, priority, max_retries, scheduled_at)
   values (?, ?, ?, ?, ?, ?, clock_timestamp() + ?)
   returning id")

(defn submit!
  ([opts] (submit! db/pool opts))
  ([conn {:keys [name delay props queue priority max-retries]
          :or {delay 0 props {} queue "default" priority 100 max-retries 3}
          :as options}]
   (us/verify ::task-options options)
   (let [duration  (dt/duration delay)
         interval  (db/interval duration)
         props     (db/tjson props)
         id        (uuid/next)]
     (log/info (str/format "Submit task '%s' to be executed in '%s'." name (str duration)))
     (db/exec-one! conn [sql:insert-new-task id name props queue priority max-retries interval])
     id)))

(mtx/instrument-with-counter!
 {:var #'submit!
  :id "tasks__submit_counter"
  :help "Absolute task submit counter."})

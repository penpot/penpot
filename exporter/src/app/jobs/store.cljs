;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.jobs.store
  "Redis persistence for export jobs.

  Stores each job as a single blob with a TTL matching the exported file,
  so records expire with their files.

  Reads use Redis. Cancellation requires the process running the job."
  (:require
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.config :as cf]
   [app.redis :as redis]
   [promesa.core :as p]))

(def ^:private cancel-topic (redis/->key "job-cancel"))

(defn- job-key
  [job-id]
  (redis/->key "job." job-id))

(defn- ttl
  []
  (cf/get :exporter-job-ttl 3600))

(defn persist!
  "Writes the job record and refreshes its TTL.

  If the write fails, log it and continue. The export still runs
  and publishes websocket updates, but the job can't be fetched
  afterward (fetch returns nil, REST returns 404)."
  [{:keys [id state] :as job}]
  (let [jkey (job-key id)]
    (->> (p/do
           (redis/hset! jkey {:data (t/encode-str job)})
           (redis/expire! jkey (ttl))
           job)
         (p/merr (fn [cause]
                   (if (= :redis-not-available (:code (ex-data cause)))
                     (l/warn :hint "job record not persisted, no redis connection"
                             :job-id (str id) :state state)
                     (l/error :hint "unable to persist job record"
                              :job-id (str id) :state state :cause cause))
                   (p/resolved job))))))

(defn fetch
  "The job record, or nil when unknown or expired."
  [job-id]
  (->> (redis/hgetall (job-key job-id))
       (p/fmap (fn [data]
                 (when-let [blob (get data "data")]
                   (try
                     (t/decode-str blob)
                     (catch :default cause
                       (l/warn :hint "unable to decode job record" :job-id (str job-id) :cause cause)
                       nil)))))))

(defn fetch-all
  []
  (->> (redis/scan (redis/->key "job.*"))
       (p/mcat (fn [keys]
                 (->> (map (fn [k]
                             (->> (redis/hgetall k)
                                  (p/fmap (fn [data]
                                            (when-let [blob (get data "data")]
                                              (try
                                                (t/decode-str blob)
                                                (catch :default _ nil)))))))
                           keys)
                      (p/all))))
       (p/fmap (fn [jobs] (vec (remove nil? jobs))))))

(defn request-cancel!
  "Asks every exporter to cancel `job-id`. Only the one running it will act."
  [job-id]
  (redis/pub! cancel-topic (str job-id)))

(defn on-cancel-request
  "Registers `handler` (fn of the job-id string) for cancel requests. Returns an
  unsubscribe fn."
  [handler]
  (redis/sub! cancel-topic handler))

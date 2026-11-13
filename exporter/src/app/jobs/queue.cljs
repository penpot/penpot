;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.jobs.queue
  "Shared export queue for distributed deployments.

  An `api` process pushes job ids onto one redis list; `worker` processes
  claim them. A claim moves the id atomically into the worker's own
  processing list, so no two workers get the same job, and a job held by a
  worker that dies is still on record and can be put back (`reap!`).

  What a worker needs to rebuild the export travels in a payload key of its
  own, never in the job record: the record is served to clients, and the
  payload holds the session token (sealed, see `app.util.crypto`).

  A job is queued at most once. Enqueueing takes an `enqueued` marker for
  the job id with `SET NX`; the marker lives while the job waits or is
  claimed, and goes when the job leaves (`ack!`, `remove-queued!`) or
  expires. A second enqueue of the same id finds the marker and is dropped,
  so two workers never get the same job from two pushes."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.instance :as instance]
   [app.redis :as redis]
   [app.util.crypto :as crypto]
   [promesa.core :as p]))

(defn queue-key
  []
  (redis/->key "queue"))

(defn processing-key
  [instance-id]
  (redis/->key "processing." instance-id))

(defn payload-key
  [job-id]
  (redis/->key "payload." job-id))

(defn enqueued-key
  [job-id]
  (redis/->key "enqueued." job-id))

(defn- ttl
  []
  (cf/get :exporter-job-ttl 3600))

(defn depth
  "Number of jobs waiting to be claimed."
  []
  (redis/llen (queue-key)))

(defn check-capacity!
  "Rejects new work once the shared queue holds `exporter-queue-max` jobs,
  the same limit a single process applies to its local queue."
  []
  (->> (depth)
       (p/fmap (fn [n]
                 (when (>= n (cf/get :exporter-queue-max 64))
                   (ex/raise :type :validation
                             :code :queue-full
                             :hint "too many queued export jobs"))
                 n))))

(defn- invalid-job-id
  [id]
  (p/rejected (ex/error :type :validation
                        :code :invalid-job-id
                        :hint (str "invalid export job id: " (pr-str id)))))

(defn enqueue!
  "Stores what a worker needs to run `job` and queues its id.
  `payload` is `{:cmd :params :auth-token}`.

  Resolves to true when the job was queued, false when the same id is
  already queued or claimed (nothing is written then, so the first payload
  stays). Rejects with `:invalid-job-id` unless the id is a UUID."
  [{:keys [id] :as _job} payload]
  (if-let [id (uuid/coerce id)]
    (let [marker (enqueued-key id)]
      (->> (redis/set-nx-ex! marker "1" (ttl))
           (p/mcat (fn [fresh?]
                     (if-not fresh?
                       (do (l/dbg :hint "export job already queued, not queueing again" :job-id (str id))
                           (p/resolved false))
                       (let [payload (update payload :auth-token #(some-> % crypto/encrypt))]
                         (->> (p/do
                                (redis/set-ex! (payload-key id) (t/encode-str payload) (ttl))
                                (redis/rpush! (queue-key) (str id))
                                true)
                              ;; Not queued after all: drop the marker, or it
                              ;; would turn away a retry until it expired.
                              (p/merr (fn [cause]
                                        (->> (redis/del! marker)
                                             (p/merr (constantly (p/resolved nil)))
                                             (p/mcat (fn [_] (p/rejected cause)))))))))))))
    (invalid-job-id id)))

(defn payload
  "The payload of a queued job, token opened, or nil when it expired."
  [job-id]
  (->> (redis/get-key (payload-key job-id))
       (p/fmap (fn [blob]
                 (when blob
                   (-> (t/decode-str blob)
                       (update :auth-token #(some-> % crypto/decrypt))))))))

(defn claim!
  "Waits up to `timeout` seconds for a job and claims it for this process.
  Resolves to the job id, or nil when none arrived."
  [timeout]
  (redis/blmove! (queue-key) (processing-key instance/id) "LEFT" "RIGHT" timeout))

(defn ack!
  "Releases a job this process claimed, once it settled."
  [job-id]
  (->> (p/do
         (redis/lrem! (processing-key instance/id) (str job-id))
         (redis/del! (payload-key job-id))
         (redis/del! (enqueued-key job-id)))
       (p/merr (fn [cause]
                 (l/warn :hint "unable to release claimed job" :job-id (str job-id) :cause cause)
                 (p/resolved nil)))))

(defn remove-queued!
  "Takes a job that nobody claimed yet off the queue, along with its payload.
  Resolves to true when it was still there."
  [job-id]
  (->> (redis/lrem! (queue-key) (str job-id))
       (p/mcat (fn [n]
                 (if (pos? n)
                   (->> (p/do
                          (redis/del! (payload-key job-id))
                          (redis/del! (enqueued-key job-id)))
                        (p/fmap (constantly true)))
                   (p/resolved false))))))

(defn- drain!
  "Moves every job held by `instance-id` back to the head of the queue. Each
  move is atomic, so two processes reaping the same instance never both get
  a job."
  [instance-id]
  (let [src (processing-key instance-id)]
    (letfn [(step [moved]
              (->> (redis/lmove! src (queue-key) "RIGHT" "LEFT")
                   (p/mcat (fn [job-id]
                             (if job-id
                               (step (conj moved job-id))
                               (p/resolved moved))))))]
      (step []))))

(defn reap!
  "Hands back the jobs of every instance whose heartbeat expired, and forgets
  those instances. Resolves to the ids put back on the queue."
  []
  (->> (instance/members)
       (p/mcat (fn [ids]
                 (->> ids
                      (remove #(= instance/id %))
                      (map (fn [instance-id]
                             (->> (instance/alive? instance-id)
                                  (p/mcat (fn [alive?]
                                            (if alive?
                                              (p/resolved [])
                                              (->> (drain! instance-id)
                                                   (p/mcat (fn [moved]
                                                             (when (seq moved)
                                                               (l/warn :hint "requeued jobs of a lost instance"
                                                                       :instance instance-id
                                                                       :count (count moved)))
                                                             (->> (instance/forget! instance-id)
                                                                  (p/fmap (constantly moved))))))))))))
                      (p/all))))
       (p/fmap (fn [moved] (into [] cat moved)))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.handlers.export
  "Handle export jobs.

  In the `all` role a job runs in the process that received it. In the `api`
  role it goes onto the shared queue instead, and a `worker` process rebuilds
  it from the queued payload (`run-claimed!`)."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.handlers.export-frames :as export-frames]
   [app.handlers.export-shapes :as export-shapes]
   [app.jobs :as jobs]
   [app.jobs.queue :as queue]
   [app.jobs.scheduler :as scheduler]
   [app.jobs.utils :as job.utils]
   [clojure.spec.alpha :as s]
   [promesa.core :as p]))

;; --- PARAMS

(defmulti command-spec :cmd)

(s/def ::cmd ::us/keyword)
(s/def ::wait ::us/boolean)

(defmethod command-spec :export-shapes [_] ::export-shapes/params)
(defmethod command-spec :export-frames [_] ::export-frames/params)

(s/def ::params
  (s/and (s/keys :req-un [::cmd]
                 :opt-un [::wait])
         (s/multi-spec command-spec :cmd)))

(defn conform-params
  [params]
  (us/conform ::params params))

(defn- prepare
  [cmd auth-token params]
  (case cmd
    :export-shapes (export-shapes/prepare auth-token params)
    :export-frames (export-frames/prepare auth-token params)))

(defn- current
  [job]
  (or (jobs/lookup (:id job)) job))

(defn- run-and-track
  [job run]
  (->> (p/do (run job))
       (p/mcat (fn [resource]
                 (->> (jobs/complete! (current job) resource)
                      (p/fmap (constantly resource)))))
       (p/merr (fn [cause]
                 (if (jobs/cancelled? (:id job))
                   (p/rejected cause)
                   (->> (jobs/fail! (current job) cause)
                        (p/mcat (fn [_] (p/rejected cause)))))))))

(defn- run-now!
  "Runs the job as soon as it is created, outside the scheduler."
  [job]
  (->> (p/do (jobs/start! job))
       (p/mcat (fn [job] (p/do ((jobs/run-fn (:id job)) job))))
       (p/fnly (fn [_ _]
                 (jobs/release! (:id job))
                 (job.utils/release! (:id job))))))

(defn- job-attrs
  [cmd profile-id {:keys [resource total headless]}]
  {:profile-id profile-id
   :cmd cmd
   ;; Backend used for admission and cancel UI: headless WASM when `:is-wasm`
   ;; is set, otherwise browser.
   :backend (if headless "wasm" "browser")
   :total total
   :name (:name resource)
   :resource-id (:id resource)})

(defn- create!
  [auth-token {:keys [cmd profile-id] :as params} start]
  (let [{:keys [resource run] :as prepared} (prepare cmd auth-token params)]
    (->> (jobs/create! (job-attrs cmd profile-id prepared)
                       (fn [job] (run-and-track job run)))
         (p/fmap (fn [job]
                   (try
                     {:job job
                      :resource resource
                      :pending (start job)}
                     (catch :default cause
                       (jobs/fail! job cause)
                       (jobs/release! (:id job))
                       (throw cause))))))))

(defn- enqueue!
  "Records the job and puts it on the shared queue. Preparing here only sizes
  the job and names its resource; the worker prepares it again to render.
  Resolves to `{:job :resource}`."
  [auth-token {:keys [cmd profile-id] :as params}]
  (->> (queue/check-capacity!)
       (p/mcat (fn [_]
                 (let [{:keys [resource] :as prepared} (prepare cmd auth-token params)]
                   (->> (jobs/create-queued! (job-attrs cmd profile-id prepared))
                        (p/mcat (fn [job]
                                  (->> (queue/enqueue! job {:cmd cmd
                                                            :params (-> params
                                                                        (dissoc :wait)
                                                                        (assoc :resource-id (:id resource)))
                                                            :auth-token auth-token})
                                       (p/fmap (constantly {:job job
                                                            :resource (dissoc resource :path)}))
                                       ;; Recorded but never queued: nothing
                                       ;; would ever settle it.
                                       (p/merr (fn [cause]
                                                 (->> (jobs/fail-unowned! job "unable to queue export job")
                                                      (p/mcat (fn [_] (p/rejected cause)))))))))))))))

(defn- settled-resource
  "The resource of a job another process ran, as the original route answers
  it once the export finished."
  [{:keys [state error resource-id name filename mtype resource-uri size]}]
  (if (= "ended" state)
    (d/without-nils {:id resource-id
                     :name name
                     :filename filename
                     :mtype mtype
                     :uri resource-uri
                     :size size})
    (ex/raise :type :internal
              :code :export-failed
              :hint (or error (str "export job " state)))))

(defn create-job!
  "Returns a promise of `{:job :resource :pending}`."
  [auth-token params]
  (if (cf/distributed?)
    (->> (enqueue! auth-token params)
         (p/fmap #(assoc % :pending (p/resolved nil))))
    (create! auth-token params scheduler/submit!)))

(defn export!
  "Like `create-job!`, but the work starts right away: This is what
  keeps browser exports behaving exactly as they did before there were jobs.

  Distributed, it is queued like any other job, and `:pending` follows it to
  the worker when the caller waits for the file."
  [auth-token {:keys [wait] :as params}]
  (if (cf/distributed?)
    (->> (enqueue! auth-token params)
         (p/fmap (fn [{:keys [job] :as result}]
                   (assoc result :pending
                          (if wait
                            (->> (jobs/await-settled (:id job) (* 1000 (cf/get :exporter-wait-timeout 300)))
                                 (p/fmap settled-resource))
                            (p/resolved nil))))))
    (create! auth-token params run-now!)))

(defn run-claimed!
  "Runs a job claimed from the shared queue, through the local scheduler.
  The export is prepared inside the job's run, so a payload that no longer
  prepares fails the job instead of leaving it queued. Resolves once the job
  settled."
  [job {:keys [cmd params auth-token]}]
  (->> (jobs/adopt! job
                    (fn [job]
                      (run-and-track job
                                     (fn [job]
                                       (let [{:keys [run]} (prepare cmd auth-token params)]
                                         (run job))))))
       (p/mcat scheduler/submit!)))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.handlers.export
  "Handle export jobs"
  (:require
   [app.common.spec :as us]
   [app.handlers.export-frames :as export-frames]
   [app.handlers.export-shapes :as export-shapes]
   [app.jobs :as jobs]
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

(defn- create!
  [auth-token {:keys [cmd profile-id] :as params} start]
  (let [{:keys [resource total headless run]} (prepare cmd auth-token params)]
    (->> (jobs/create! {:profile-id profile-id
                        :cmd cmd
                        ;; What the renderer will actually do, not what the
                        ;; client asked for: `is-wasm` alone still renders in
                        ;; the browser without the `wasm-export` flag, or for
                        ;; svg, and the backend decides both the admission cap
                        ;; and whether the client offers to cancel.
                        :backend (if headless "wasm" "browser")
                        :total total
                        :name (:name resource)
                        :resource-id (:id resource)}
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

(defn create-job!
  "Returns a promise of `{:job :resource :pending}`."
  [auth-token params]
  (create! auth-token params scheduler/submit!))

(defn export!
  "Like `create-job!`, but the work starts right away: This is what
  keeps browser exports behaving exactly as they did before there were jobs."
  [auth-token params]
  (create! auth-token params run-now!))

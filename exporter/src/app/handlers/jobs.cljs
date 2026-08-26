;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.handlers.jobs
  "REST surface for export jobs, under `/api/export/jobs`.

  Ownership always comes from the session (see `app.auth`), never from the
  request body."
  (:require
   [app.auth :as auth]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.handlers.export :as export]
   [app.jobs :as jobs]
   [promesa.core :as p]))

(defn create
  [{:keys [:request/auth-token :request/params] :as exchange}]
  (->> (auth/require-profile-id auth-token)
       (p/mcat (fn [profile-id]
                 (let [params (-> params
                                  (assoc :profile-id profile-id)
                                  (export/conform-params))]
                   (l/dbg :hint "create export job" :cmd (:cmd params) :profile-id (str profile-id))
                   (export/create-job! auth-token params))))
       (p/fmap (fn [{:keys [job resource pending]}]
                 ;; A failure is reported through the job record, so the
                 ;; promise must not surface as an unhandled rejection.
                 (p/merr (constantly nil) pending)
                 (-> exchange
                     (assoc :response/body (-> (or (jobs/lookup (:id job)) job)
                                               (assoc :filename (:filename resource))
                                               (assoc :mtype (:mtype resource)))))))))

(defn fetch
  [{:keys [:request/auth-token] :as exchange} job-id]
  (->> (auth/require-profile-id auth-token)
       (p/mcat (fn [profile-id]
                 (->> (jobs/fetch job-id)
                      (p/fmap #(auth/check-owner! % profile-id)))))
       (p/fmap (fn [job]
                 (assoc exchange :response/body job)))))

(defn cancel
  [{:keys [:request/auth-token] :as exchange} job-id]
  (->> (auth/require-profile-id auth-token)
       (p/mcat (fn [profile-id]
                 (->> (jobs/fetch job-id)
                      (p/fmap #(auth/check-owner! % profile-id)))))
       (p/mcat (fn [job] (jobs/cancel! (:id job))))
       (p/mcat (fn [_] (jobs/fetch job-id)))
       (p/fmap (fn [job]
                 (if job
                   (assoc exchange :response/body job)
                   (ex/raise :type :not-found
                             :code :object-not-found
                             :hint "job does not exist"))))))

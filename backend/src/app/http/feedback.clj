;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http.feedback
  "A general purpose feedback module."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.db :as db]
   [app.emails :as eml]
   [app.rpc.queries.profile :as profile]
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [promesa.core :as p]
   [promesa.exec :as px]
   [yetti.request :as yrq]
   [yetti.response :as yrs]))

(declare ^:private send-feedback)
(declare ^:private handler)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool ::wrk/executor]))

(defmethod ig/init-key ::handler
  [_ {:keys [executor] :as cfg}]
  (let [enabled? (contains? cf/flags :user-feedback)]
    (if enabled?
      (fn [request respond raise]
        (-> (px/submit! executor #(handler cfg request))
            (p/then' respond)
            (p/catch raise)))
      (fn [_ _ raise]
        (raise (ex/error :type :validation
                         :code :feedback-disabled
                         :hint "feedback module is disabled"))))))

(defn- handler
  [{:keys [pool] :as cfg} {:keys [profile-id] :as request}]
  (let [ftoken (cf/get :feedback-token ::no-token)
        token  (yrq/get-header request "x-feedback-token")
        params (d/merge (:params request)
                        (:body-params request))]
    (cond
      (uuid? profile-id)
      (let [profile (profile/retrieve-profile-data pool profile-id)
            params  (assoc params :from (:email profile))]
        (send-feedback pool profile params))

      (= token ftoken)
      (send-feedback cfg nil params))

    (yrs/response 204)))

(s/def ::content ::us/string)
(s/def ::from    ::us/email)
(s/def ::subject ::us/string)
(s/def ::feedback
  (s/keys :req-un [::from ::subject ::content]))

(defn- send-feedback
  [pool profile params]
  (let [params      (us/conform ::feedback params)
        destination (cf/get :feedback-destination)]
    (eml/send! {::eml/conn pool
                ::eml/factory eml/feedback
                :from     destination
                :to       destination
                :profile  profile
                :reply-to (:from params)
                :email    (:from params)
                :subject  (:subject params)
                :content  (:content params)})
    nil))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

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
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(declare send-feedback)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool]))

(defmethod ig/init-key ::handler
  [_ {:keys [pool] :as scfg}]
  (let [ftoken   (cf/get :feedback-token ::no-token)
        enabled  (contains? cf/flags :user-feedback)]
    (fn [{:keys [profile-id] :as request}]
      (let [token  (get-in request [:headers "x-feedback-token"])
            params (d/merge (:params request)
                            (:body-params request))]

        (when-not enabled
          (ex/raise :type :validation
                    :code :feedback-disabled
                    :hint "feedback module is disabled"))

        (cond
          (uuid? profile-id)
          (let [profile (profile/retrieve-profile-data pool profile-id)
                params  (assoc params :from (:email profile))]
            (when-not (:is-muted profile)
              (send-feedback pool profile params)))

          (= token ftoken)
          (send-feedback scfg nil params))

        {:status 204 :body ""}))))

(s/def ::content ::us/string)
(s/def ::from    ::us/email)
(s/def ::subject ::us/string)

(s/def ::feedback
  (s/keys :req-un [::from ::subject ::content]))

(defn send-feedback
  [pool profile params]
  (let [params      (us/conform ::feedback params)
        destination (cf/get :feedback-destination)]
    (eml/send! {::eml/conn pool
                ::eml/factory eml/feedback
                :to       destination
                :profile  profile
                :reply-to (:from params)
                :email    (:from params)
                :subject  (:subject params)
                :content  (:content params)})
    nil))

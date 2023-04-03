;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.feedback
  "A general purpose feedback module."
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.db :as db]
   [app.email :as eml]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.profile :as profile]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

(declare ^:private send-feedback!)

(s/def ::content ::us/string)
(s/def ::from    ::us/email)
(s/def ::subject ::us/string)

(s/def ::send-user-feedback
  (s/keys :req [::rpc/profile-id]
          :req-un [::subject
                   ::content]))

(sv/defmethod ::send-user-feedback
  {::doc/added "1.18"}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id] :as params}]
  (when-not (contains? cf/flags :user-feedback)
    (ex/raise :type :restriction
              :code :feedback-disabled
              :hint "feedback not enabled"))

  (let [profile (profile/get-profile pool profile-id)]
    (send-feedback! pool profile params)
    nil))

(defn- send-feedback!
  [pool profile params]
  (let [dest (cf/get :feedback-destination)]
    (eml/send! {::eml/conn pool
                ::eml/factory eml/feedback
                :from     dest
                :to       dest
                :profile  profile
                :reply-to (:email profile)
                :email    (:email profile)
                :subject  (:subject params)
                :content  (:content params)})
    nil))

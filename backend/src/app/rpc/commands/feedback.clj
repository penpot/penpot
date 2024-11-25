;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.feedback
  "A general purpose feedback module."
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.db :as db]
   [app.email :as eml]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.profile :as profile]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]))

(declare ^:private send-user-feedback!)

(def ^:private schema:send-user-feedback
  [:map {:title "send-user-feedback"}
   [:subject [:string {:max 400}]]
   [:content [:string {:max 2500}]]])

(sv/defmethod ::send-user-feedback
  {::doc/added "1.18"
   ::sm/params schema:send-user-feedback}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id] :as params}]
  (when-not (contains? cf/flags :user-feedback)
    (ex/raise :type :restriction
              :code :feedback-disabled
              :hint "feedback not enabled"))

  (let [profile (profile/get-profile pool profile-id)]
    (send-user-feedback! pool profile params)
    nil))

(defn- send-user-feedback!
  [pool profile params]
  (let [dest (or (cf/get :user-feedback-destination)
                 ;; LEGACY
                 (cf/get :feedback-destination))]
    (eml/send! {::eml/conn pool
                ::eml/factory eml/user-feedback
                :from     dest
                :to       dest
                :profile  profile
                :reply-to (:email profile)
                :email    (:email profile)
                :subject  (:subject params)
                :content  (:content params)})
    nil))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2021 UXBOX Labs SL

(ns app.rpc.mutations.feedback
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.db :as db]
   [app.emails :as emails]
   [app.rpc.queries.profile :as profile]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

(s/def ::subject ::us/string)
(s/def ::content ::us/string)

(s/def ::send-profile-feedback
  (s/keys :req-un [::profile-id ::subject ::content]))

(sv/defmethod ::send-profile-feedback
  [{:keys [pool] :as cfg} {:keys [profile-id subject content] :as params}]
  (when-not (:feedback-enabled cfg/config)
    (ex/raise :type :validation
              :code :feedback-disabled
              :hint "feedback module is disabled"))

  (db/with-atomic [conn pool]
    (let [profile (profile/retrieve-profile-data conn profile-id)]
      (emails/send! conn emails/feedback
                    {:to (:feedback-destination cfg/config)
                     :profile profile
                     :subject subject
                     :content content})
      nil)))

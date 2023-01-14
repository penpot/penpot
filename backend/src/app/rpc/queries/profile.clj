;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.queries.profile
  (:require
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.profile :as profile]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

(s/def ::profile ::profile/get-profile)

(sv/defmethod ::profile
  {::rpc/auth false
   ::doc/added "1.0"
   ::doc/deprecated "1.18"}
  [{:keys [::db/pool] :as cfg} {:keys [profile-id]}]
  ;; We need to return the anonymous profile object in two cases, when
  ;; no profile-id is in session, and when db call raises not found. In all other
  ;; cases we need to reraise the exception.
  (try
    (-> (profile/get-profile pool profile-id)
        (profile/strip-private-attrs)
        (update :props profile/filter-props))
    (catch Throwable _
      {:id uuid/zero :fullname "Anonymous User"})))

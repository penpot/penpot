;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.http.auth
  (:require
   [app.http.session :as session]))

(defn login-handler
  [{:keys [session rpc] :as cfg} request]
  (let [data    (:params request)
        uagent  (get-in request [:headers "user-agent"])
        method  (get-in rpc [:methods :mutation :login])
        profile (method data)
        id      (session/create! session {:profile-id (:id profile)
                                          :user-agent uagent})]
    {:status 200
     :cookies (session/cookies session {:value id})
     :body profile}))

(defn logout-handler
  [{:keys [session] :as cfg} request]
  (session/delete! cfg request)
  {:status 200
   :cookies (session/cookies session {:value "" :max-age -1})
   :body ""})

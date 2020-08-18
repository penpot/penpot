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
   [app.common.exceptions :as ex]
   [app.common.uuid :as uuid]
   [app.http.session :as session]
   [app.services.mutations :as sm]))

(defn login-handler
  [req]
  (let [data   (:body-params req)
        uagent (get-in req [:headers "user-agent"])]
    (let [profile (sm/handle (assoc data ::sm/type :login))
          id      (session/create (:id profile) uagent)]
      {:status 200
       :cookies (session/cookies id)
       :body profile})))

(defn logout-handler
  [req]
  (some-> (session/extract-auth-token req)
          (session/delete))
  {:status 200
   :cookies (session/cookies "" {:max-age -1})
   :body ""})

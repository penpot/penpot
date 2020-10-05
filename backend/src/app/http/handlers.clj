;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.http.handlers
  (:require
   [app.common.exceptions :as ex]
   [app.emails :as emails]
   [app.http.session :as session]
   [app.services.init]
   [app.services.mutations :as sm]
   [app.services.queries :as sq]))

(def unauthorized-services
  #{:create-demo-profile
    :logout
    :profile
    :verify-profile-token
    :recover-profile
    :register-profile
    :request-profile-recovery
    :viewer-bundle
    :login})

(defn query-handler
  [req]
  (let [type (keyword (get-in req [:path-params :type]))
        data (merge (:params req)
                    {::sq/type type})
        data (cond-> data
               (:profile-id req) (assoc :profile-id (:profile-id req)))]
    (if (or (:profile-id req) (contains? unauthorized-services type))
      {:status 200
       :body (sq/handle (with-meta data {:req req}))}
      {:status 403
       :body {:type :authentication
              :code :unauthorized}})))

(defn mutation-handler
  [req]
  (let [type (keyword (get-in req [:path-params :type]))
        data (merge (:params req)
                    (:body-params req)
                    (:uploads req)
                    {::sm/type type})
        data (cond-> data
               (:profile-id req) (assoc :profile-id (:profile-id req)))]
    (if (or (:profile-id req) (contains? unauthorized-services type))
      (let [result (sm/handle (with-meta data {:req req}))
            mdata  (meta result)
            resp   {:status (if (nil? (seq result)) 204 200)
                    :body result}]
        (cond->> resp
          (:transform-response mdata) ((:transform-response mdata) req)))

      {:status 403
       :body {:type :authentication
              :code :unauthorized}})))

(defn echo-handler
  [req]
  {:status 200
   :body {:params (:params req)
          :cookies (:cookies req)
          :headers (:headers req)}})


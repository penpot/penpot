;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.http.handlers
  #_(:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.emails :as emails]
   [app.http.session :as session]
   [app.services.init]
   [app.services.mutations :as sm]
   [app.services.queries :as sq]
   [app.services.svgparse :as svgp]))

;; (def unauthorized-services
;;   #{:create-demo-profile
;;     :logout
;;     :profile
;;     :verify-token
;;     :recover-profile
;;     :register-profile
;;     :request-profile-recovery
;;     :viewer-bundle
;;     :login})

;; (defn query-handler
;;   [cfg {:keys [profile-id] :as request}]
;;   (let [type (keyword (get-in request [:path-params :type]))
;;         data (assoc (:params request) ::sq/type type)
;;         data (if profile-id
;;                (assoc data :profile-id profile-id)
;;                (dissoc data :profile-id))]

;;     (if (or (uuid? profile-id)
;;             (contains? unauthorized-services type))
;;       {:status 200
;;        :body (sq/handle (with-meta data {:req request}))}
;;       {:status 403
;;        :body {:type :authentication
;;               :code :unauthorized}})))

;; (defn mutation-handler
;;   [cfg {:keys [profile-id] :as request}]
;;   (let [type (keyword (get-in request [:path-params :type]))
;;         data (d/merge (:params request)
;;                       (:body-params request)
;;                       (:uploads request)
;;                       {::sm/type type})
;;         data (if profile-id
;;                (assoc data :profile-id profile-id)
;;                (dissoc data :profile-id))]

;;     (if (or (uuid? profile-id)
;;             (contains? unauthorized-services type))
;;       (let [result (sm/handle (with-meta data {:req request}))
;;             mdata  (meta result)
;;             resp   {:status (if (nil? (seq result)) 204 200)
;;                     :body result}]
;;         (cond->> resp
;;           (:transform-response mdata) ((:transform-response mdata) request)))
;;       {:status 403
;;        :body {:type :authentication
;;               :code :unauthorized}})))

;; (defn parse-svg
;;   [{:keys [headers body] :as request}]
;;   (when (not= "image/svg+xml" (get headers "content-type"))
;;     (ex/raise :type :validation
;;               :code :unsupported-mime-type
;;               :mime (get headers "content-type")))
;;   {:status 200
;;    :body (svgp/parse body)})

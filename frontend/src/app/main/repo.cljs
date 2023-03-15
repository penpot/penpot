;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.repo
  (:require
   [app.common.data :as d]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.util.http :as http]
   [beicon.core :as rx]))

(derive :get-all-projects ::query)
(derive :get-comment-threads ::query)
(derive :get-file ::query)
(derive :get-file-fragment ::query)
(derive :get-file-libraries ::query)
(derive :get-file-object-thumbnails ::query)
(derive :get-font-variants ::query)
(derive :get-profile ::query)
(derive :get-project ::query)
(derive :get-projects ::query)
(derive :get-team-invitations ::query)
(derive :get-team-members ::query)
(derive :get-team-shared-files ::query)
(derive :get-team-stats ::query)
(derive :get-team-users ::query)
(derive :get-teams ::query)
(derive :get-view-only-bundle ::query)
(derive :search-files ::query)
(derive :retrieve-list-of-builtin-templates ::query)
(derive :get-unread-comment-threads ::query)
(derive :get-team-recent-files ::query)

(defn handle-response
  [{:keys [status body] :as response}]
  (cond
    (= 204 status)
    ;; We need to send "something" so the streams listening downstream can act
    (rx/of nil)

    (= 502 status)
    (rx/throw {:type :bad-gateway})

    (= 503 status)
    (rx/throw {:type :service-unavailable})

    (= 0 (:status response))
    (rx/throw {:type :offline})

    (= 200 status)
    (rx/of body)

    (= 413 status)
    (rx/throw {:type :validation
               :code :request-body-too-large})

    (and (>= status 400) (map? body))
    (rx/throw body)

    :else
    (rx/throw {:type :unexpected-error
               :status status
               :data body})))

(defn- send-query!
  "A simple helper for send and receive transit data on the penpot
  query api."
  ([id params]
   (send-query! id params nil))
  ([id params {:keys [raw-transit?]}]
   (let [decode-transit (if raw-transit?
                          http/conditional-error-decode-transit
                          http/conditional-decode-transit)]
     (->> (http/send! {:method :get
                       :uri (u/join @cf/public-uri "api/rpc/query/" (name id))
                       :headers {"accept" "application/transit+json"}
                       :credentials "include"
                       :query params})
          (rx/map decode-transit)
          (rx/mapcat handle-response)))))

(defn- send-mutation!
  "A simple helper for a common case of sending and receiving transit
  data to the penpot mutation api."
  [id params]
  (->> (http/send! {:method :post
                    :uri (u/join @cf/public-uri "api/rpc/mutation/" (name id))
                    :headers {"accept" "application/transit+json"}
                    :credentials "include"
                    :body (http/transit-data params)})
       (rx/map http/conditional-decode-transit)
       (rx/mapcat handle-response)))

(defn- send-command!
  "A simple helper for a common case of sending and receiving transit
  data to the penpot mutation api."
  [id params {:keys [response-type form-data? raw-transit?]}]
  (let [decode-fn (if raw-transit?
                    http/conditional-error-decode-transit
                    http/conditional-decode-transit)
        method    (if (isa? id ::query) :get :post)]

    (->> (http/send! {:method method
                      :uri (u/join @cf/public-uri "api/rpc/command/" (name id))
                      :credentials "include"
                      :headers {"accept" "application/transit+json"}
                      :body (when (= method :post)
                              (if form-data?
                                (http/form-data params)
                                (http/transit-data params)))
                      :query (when (= method :get)
                               params)
                      :response-type (or response-type :text)})
         (rx/map decode-fn)
         (rx/mapcat handle-response))))

(defn- dispatch [& args] (first args))

(defmulti query dispatch)
(defmulti mutation dispatch)
(defmulti command dispatch)

(defmethod query :default
  [id params]
  (send-query! id params))

(defmethod command :get-raw-file
  [_id params]
  (send-command! :get-file params {:raw-transit? true}))

(defmethod mutation :default
  [id params]
  (send-mutation! id params))

(defmethod command :default
  [id params]
  (send-command! id params nil))

(defmethod command :export-binfile
  [id params]
  (send-command! id params {:response-type :blob}))

(defmethod command :import-binfile
  [id params]
  (send-command! id params {:form-data? true}))

(defn query!
  ([id] (query id {}))
  ([id params] (query id params)))

(defn mutation!
  ([id] (mutation id {}))
  ([id params] (mutation id params)))

(defn command!
  ([id] (command id {}))
  ([id params] (command id params)))

(defn cmd!
  ([id] (command id {}))
  ([id params] (command id params)))

(defmethod command :login-with-oidc
  [_ {:keys [provider] :as params}]
  (let [uri    (u/join @cf/public-uri "api/auth/oauth/" (d/name provider))
        params (dissoc params :provider)]
    (->> (http/send! {:method :post
                      :uri uri
                      :credentials "include"
                      :query params})
         (rx/map http/conditional-decode-transit)
         (rx/mapcat handle-response))))

(defn- send-export
  [{:keys [blob?] :as params}]
  (->> (http/send! {:method :post
                    :uri (u/join @cf/public-uri "api/export")
                    :body (http/transit-data (dissoc params :blob?))
                    :credentials "include"
                    :response-type (if blob? :blob :text)})
       (rx/map http/conditional-decode-transit)
       (rx/mapcat handle-response)))

(defmethod command :export
  [_ params]
  (let [default {:wait false :blob? false}]
    (send-export (merge default params))))

(derive :upload-file-media-object ::multipart-upload)
(derive :update-profile-photo ::multipart-upload)
(derive :update-team-photo ::multipart-upload)

(defmethod mutation ::multipart-upload
  [id params]
  (->> (http/send! {:method :post
                    :uri  (u/join @cf/public-uri "api/rpc/mutation/" (name id))
                    :credentials "include"
                    :body (http/form-data params)})
       (rx/map http/conditional-decode-transit)
       (rx/mapcat handle-response)))

(defmethod command ::multipart-upload
  [id params]
  (->> (http/send! {:method :post
                    :uri  (u/join @cf/public-uri "api/rpc/command/" (name id))
                    :credentials "include"
                    :body (http/form-data params)})
       (rx/map http/conditional-decode-transit)
       (rx/mapcat handle-response)))

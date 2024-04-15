;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.repo
  (:require
   [app.common.data :as d]
   [app.common.transit :as t]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.util.http :as http]
   [app.util.sse :as sse]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

(defn handle-response
  [{:keys [status body] :as response}]
  (cond
    (= 204 status)
    ;; We need to send "something" so the streams listening downstream can act
    (rx/of nil)

    (= 502 status)
    (rx/throw (ex-info "http error" {:type :bad-gateway}))

    (= 503 status)
    (rx/throw (ex-info "http error" {:type :service-unavailable}))

    (= 0 (:status response))
    (rx/throw (ex-info "http error" {:type :offline}))

    (= 200 status)
    (rx/of body)

    (= 413 status)
    (rx/throw (ex-info "http error"
                       {:type :validation
                        :code :request-body-too-large}))

    (and (>= status 400) (map? body))
    (rx/throw (ex-info "http error" body))

    :else
    (rx/throw
     (ex-info "http error"
              {:type :unexpected-error
               :status status
               :data body}))))

(def default-options
  {:update-file {:query-params [:id]}
   :get-raw-file {:rename-to :get-file :raw-transit? true}

   :create-file-object-thumbnail
   {:query-params [:file-id :object-id :tag :requester]
    :form-data? true}

   :create-file-thumbnail
   {:query-params [:file-id :revn]
    :form-data? true}

   ::sse/clone-template
   {:response-type ::sse/stream}

   ::sse/import-binfile
   {:response-type ::sse/stream
    :form-data? true}

   :export-binfile {:response-type :blob}
   :retrieve-list-of-builtin-templates {:query-params :all}})

(defn- send!
  "A simple helper for a common case of sending and receiving transit
  data to the penpot mutation api."
  [id params options]
  (let [{:keys [response-type
                form-data?
                raw-transit?
                query-params
                rename-to]}
        (-> (get default-options id)
            (merge options))

        decode-fn (if raw-transit?
                    http/conditional-error-decode-transit
                    http/conditional-decode-transit)

        id        (or rename-to id)
        nid       (name id)
        method    (cond
                    (= query-params :all)  :get
                    (str/starts-with? nid "get-") :get
                    :else :post)

        request   {:method method
                   :uri (u/join cf/public-uri "api/rpc/command/" nid)
                   :credentials "include"
                   :headers {"accept" "application/transit+json,text/event-stream,*/*"}
                   :body (when (= method :post)
                           (if form-data?
                             (http/form-data params)
                             (http/transit-data params)))
                   :query (if (= method :get)
                            params
                            (if query-params
                              (select-keys params query-params)
                              nil))

                   :response-type
                   (if (= response-type ::sse/stream)
                     :stream
                     (or response-type :text))}

        result    (->> (http/send! request)
                       (rx/map decode-fn)
                       (rx/mapcat handle-response))]

    (cond->> result
      (= ::sse/stream response-type)
      (rx/mapcat (fn [body]
                   (-> (sse/create-stream body)
                       (sse/read-stream t/decode-str)))))))

(defmulti cmd! (fn [id _] id))

(defmethod cmd! :default
  [id params]
  (send! id params nil))

(defmethod cmd! :login-with-oidc
  [_ {:keys [provider] :as params}]
  (let [uri    (u/join cf/public-uri "api/auth/oauth/" (d/name provider))
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
                    :uri (u/join cf/public-uri "api/export")
                    :body (http/transit-data (dissoc params :blob?))
                    :credentials "include"
                    :response-type (if blob? :blob :text)})
       (rx/map http/conditional-decode-transit)
       (rx/mapcat handle-response)))

(defmethod cmd! :export
  [_ params]
  (let [default {:wait false :blob? false}]
    (send-export (merge default params))))

(derive :upload-file-media-object ::multipart-upload)
(derive :update-profile-photo ::multipart-upload)
(derive :update-team-photo ::multipart-upload)

(defmethod cmd! ::multipart-upload
  [id params]
  (->> (http/send! {:method :post
                    :uri  (u/join cf/public-uri "api/rpc/command/" (name id))
                    :credentials "include"
                    :body (http/form-data params)})
       (rx/map http/conditional-decode-transit)
       (rx/mapcat handle-response)))

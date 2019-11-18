;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.http.interceptors
  (:require
   [vertx.web :as vw]
   [uxbox.util.blob :as blob]
   [uxbox.util.exceptions :as ex])
  (:import
   io.vertx.ext.web.RoutingContext
   io.vertx.ext.web.FileUpload
   io.vertx.core.buffer.Buffer))

(def parse-request-body
  {:enter (fn [{:keys [request] :as data}]
            (let [body (:body request)
                  mtype (get-in request [:headers "content-type"])]
              (if (= "application/transit+json" mtype)
                (try
                  (let [params (blob/decode-from-json body)]
                    (update data :request assoc :body-params params))
                  (catch Exception e
                    (ex/raise :type :parse
                              :message "Unable to parse transit from request body."
                              :cause e)))
                data)))})

(def format-response-body
  {:leave (fn [{:keys [response] :as data}]
            (let [body (:body response)]
              (cond
                (coll? body)
                (-> data
                    (assoc-in [:response :body]
                              (blob/encode-with-json body true))
                    (update-in [:response :headers]
                               assoc "content-type" "application/transit+json"))

                (nil? body)
                (-> data
                    (assoc-in [:response :status] 204)
                    (assoc-in [:response :body] ""))

                :else
                data)))})

(def handle-uploads
  {:enter (fn [data]
            (let [rcontext (get-in data [:request ::vw/routing-context])
                  uploads (.fileUploads ^RoutingContext rcontext)
                  uploads (reduce (fn [acc ^FileUpload upload]
                                    (assoc acc
                                           (keyword (.name upload))
                                           {:type :uploaded-file
                                            :mtype (.contentType upload)
                                            :path (.uploadedFileName upload)
                                            :name (.fileName upload)
                                            :size (.size upload)}))
                                  {}
                                  uploads)]
              (assoc-in data [:request :upload-params] uploads)))})


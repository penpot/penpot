;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx.web.interceptors
  "High level api for http servers."
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [promesa.core :as p]
   [reitit.core :as r]
   [vertx.http :as vh]
   [vertx.web :as vw]
   [vertx.util :as vu]
   [sieppari.context :as spx]
   [sieppari.core :as sp])
  (:import
   clojure.lang.Keyword
   clojure.lang.MapEntry
   io.vertx.core.Future
   io.vertx.core.Handler
   io.vertx.core.MultiMap
   io.vertx.core.Vertx
   io.vertx.core.http.Cookie
   io.vertx.core.http.HttpServerRequest
   io.vertx.core.http.HttpServerResponse
   io.vertx.ext.web.FileUpload
   io.vertx.ext.web.RoutingContext
   java.util.Map
   java.util.Map$Entry))

;; --- Cookies

(defn- build-cookie
  [name data]
  (cond-> (Cookie/cookie ^String name ^String (:value data))
    (:http-only data) (.setHttpOnly true)
    (:domain data) (.setDomain (:domain data))
    (:path data) (.setPath (:path data))
    (:secure data) (.setSecure true)))

(defn cookies
  []
  {:enter
   (fn [data]
     (let [^HttpServerRequest req (get-in data [:request ::vh/request])
           parse-cookie (fn [^Cookie item] [(.getName item) (.getValue item)])
           cookies (into {} (map parse-cookie) (vals (.cookieMap req)))]
       (update data :request assoc :cookies cookies)))
   :leave
   (fn [data]
     (let [cookies (get-in data [:response :cookies])
           ^HttpServerResponse res (get-in data [:request ::vh/response])]
       (when (map? cookies)
         (vu/doseq [[key val] cookies]
           (if (nil? val)
             (.removeCookie res key)
             (.addCookie res (build-cookie key val)))))
       data))})

;; --- Params

(defn- parse-params
  [^HttpServerRequest request]
  (let [params (.params request)
        it (.iterator ^MultiMap params)]
    (loop [m (transient {})]
      (if (.hasNext it)
        (let [^Map$Entry o (.next it)
              key (keyword (.toLowerCase (.getKey o)))
              prv (get m key ::default)
              val (.getValue o)]
          (cond
            (= prv ::default)
            (recur (assoc! m key val))

            (vector? prv)
            (recur (assoc! m key (conj prv val)))

            :else
            (recur (assoc! m key [prv val]))))
        (persistent! m)))))

(defn params
  ([] (params nil))
  ([{:keys [attr] :or {attr :params}}]
   {:enter (fn [data]
             (let [request (get-in data [:request ::vh/request])
                   params (parse-params request)]
               (update data :request assoc attr params)))}))

;; --- Uploads

(defn uploads
  ([] (uploads nil))
  ([{:keys [attr] :or {attr :uploads}}]
   {:enter (fn [data]
             (let [context (get-in data [:request ::vw/routing-context])
                   uploads (reduce (fn [acc ^FileUpload upload]
                                     (assoc acc
                                            (keyword (.name upload))
                                            {:type :uploaded-file
                                             :mtype (.contentType upload)
                                             :path (.uploadedFileName upload)
                                             :name (.fileName upload)
                                             :size (.size upload)}))
                                   (transient {})
                                   (.fileUploads ^RoutingContext context))]
               (update data :request assoc attr (persistent! uploads))))}))

;; --- Errors

(defn errors
  "A error handling interceptor."
  [handler-fn]
  {:error
   (fn [data]
     (let [request (:request data)
           error (:error data)
           response (handler-fn error request)]
       (-> data
           (assoc :response response)
           (dissoc :error))))})

;; --- CORS

(s/def ::origin string?)
(s/def ::allow-credentials boolean?)
(s/def ::allow-methods (s/every keyword? :kind set?))
(s/def ::allow-headers (s/every keyword? :kind set?))
(s/def ::expose-headers (s/every keyword? :kind set?))
(s/def ::max-age number?)

(s/def ::cors-opts
  (s/keys :req-un [::origin]
          :opt-un [::allow-headers
                   ::allow-methods
                   ::expose-headers
                   ::max-age]))

(defn cors
  [opts]
  (s/assert ::cors-opts opts)
  (letfn [(preflight? [{:keys [method headers] :as ctx}]
            (and (= method :options)
                 (contains? headers "origin")
                 (contains? headers "access-control-request-method")))

          (normalize [data]
            (str/join ", " (map name data)))

          (allow-origin? [headers]
            (let [origin (:origin opts)
                  value (get headers "origin")]
              (cond
                (nil? value) value
                (= origin "*") origin
                (set? origin) (origin value)
                (= origin value) origin)))

          (get-headers [{:keys [headers] :as ctx}]
            (when-let [origin (allow-origin? headers)]
              (cond-> {"access-control-allow-origin" origin
                       "access-control-allow-methods" "GET, OPTIONS, HEAD"}

                (:allow-methods opts)
                (assoc "access-control-allow-methods"
                       (-> (normalize (:allow-methods opts))
                           (str/upper-case)))

                (:allow-credentials opts)
                (assoc "access-control-allow-credentials" "true")

                (:expose-headers opts)
                (assoc "access-control-expose-headers"
                       (-> (normalize (:expose-headers opts))
                           (str/lower-case)))

                (:max-age opts)
                (assoc "access-control-max-age" (:max-age opts))

                (:allow-headers opts)
                (assoc "access-control-allow-headers"
                       (-> (normalize (:allow-headers opts))
                           (str/lower-case))))))

          (enter [data]
            (let [ctx (:request data)]
              (if (preflight? ctx)
                (spx/terminate (assoc data ::preflight true))
                data)))

          (leave [data]
            (let [headers (get-headers (:request data))]
              (if (::preflight data)
                (assoc data :response {:status 204 :headers headers})
                (update-in data [:response :headers] merge headers))))]

    {:enter enter
     :leave leave}))

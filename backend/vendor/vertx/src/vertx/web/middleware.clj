;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx.web.middleware
  "Common middleware's."
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [promesa.core :as p]
   [reitit.core :as r]
   [vertx.http :as http]
   [vertx.web :as web]
   [vertx.util :as util])
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

(defn- handle-cookies-response
  [request {:keys [cookies] :as response}]
  (let [^HttpServerResponse res (::http/response request)]
    (util/doseq [[key val] cookies]
      (if (nil? val)
        (.removeCookie res key)
        (.addCookie res (build-cookie key val))))))

(defn- cookie->vector
  [^Cookie item]
  [(.getName item) (.getValue item)])

(defn- wrap-cookies
  [handler]
  (let [xf (map cookie->vector)]
    (fn [request]
      (let [req (::http/request request)
            cookies (.cookieMap ^HttpServerRequest req)
            cookies (into {} xf (vals cookies))]
        (-> (p/do! (handler (assoc request :cookies cookies)))
            (p/then' (fn [response]
                       (when (and (map? response)
                                  (map? (:cookies response)))
                         (handle-cookies-response request response))
                       response)))))))

(def cookies
  {:name ::cookies
   :compile (constantly wrap-cookies)})


;; --- Params

(defn- parse-params
  [^HttpServerRequest request]
  (let [params (.params request)
        it (.iterator ^MultiMap params)]
    (loop [m (transient {})]
      (if (.hasNext it)
        (let [^Map$Entry o (.next it)
              key (keyword (.toLowerCase ^String (.getKey o)))
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

(defn- wrap-params
  [handler]
  (fn [request]
    (let [req (::http/request request)
          params (parse-params req)]
      (handler (assoc request :params params)))))

(def params
  {:name ::params
   :compile (constantly wrap-params)})


;; --- Uploads

(defn- wrap-uploads
  [handler]
  (fn [request]
    (let [rctx (::web/routing-context request)
          uploads (.fileUploads ^RoutingContext rctx)
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
      (handler (assoc request :uploads uploads)))))

(def uploads
  {:name ::uploads
   :compile (constantly wrap-uploads)})

;; --- Errors

(defn- wrap-errors
  [handler on-error]
  (fn [request]
    (-> (p/do! (handler request))
        (p/catch (fn [error]
                   (on-error error request))))))

(def errors
  {:name ::errors
   :compile (constantly wrap-errors)})


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

(defn wrap-cors
  [handler opts]
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
                           (str/lower-case))))))]
    (fn [request]
      (if (preflight? request)
        {:status 204 :headers (get-headers request)}
        (-> (p/do! (handler request))
            (p/then (fn [response]
                      (if (map? response)
                        (update response :headers merge (get-headers request))
                        response))))))))

(def cors
  {:name ::cors
   :compile (constantly wrap-cors)})

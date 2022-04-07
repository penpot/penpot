;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.handlers
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.handlers.export-frames :as export-frames]
   [app.handlers.export-shapes :as export-shapes]
   [app.handlers.resources :as resources]
   [app.util.transit :as t]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [promesa.core :as p]
   [reitit.core :as r]))

(l/set-level! :debug)

(defn on-error
  [error exchange]
  (let [{:keys [type message code] :as data} (ex-data error)]
    (cond
      (or (= :validation type)
          (= :assertion type))
      (let [explain (us/pretty-explain data)
            data    (-> data
                        (assoc :explain explain)
                        (assoc :type :validation)
                        (dissoc ::s/problems ::s/value ::s/spec))]
        (-> exchange
            (assoc :response/status 400)
            (assoc :response/body (t/encode data))
            (assoc :response/headers {"content-type" "application/transit+json"})))

      (= :not-found type)
      (-> exchange
          (assoc :response/status 404)
          (assoc :response/body (t/encode data))
          (assoc :response/headers {"content-type" "application/transit+json"}))

      (and (= :internal type)
           (= :browser-not-ready code))
      (let [data {:type :server-error
                  :code :internal
                  :hint (ex-message error)
                  :data data}]
        (-> exchange
            (assoc :response/status 503)
            (assoc :response/body (t/encode data))
            (assoc :response/headers {"content-type" "application/transit+json"})))

      :else
      (let [data {:type :server-error
                  :code type
                  :hint (ex-message error)
                  :data data}]
        (l/error :hint "unexpected internal error" :cause error)
        (-> exchange
            (assoc :response/status 500)
            (assoc :response/body (t/encode (d/without-nils data)))
            (assoc :response/headers {"content-type" "application/transit+json"}))))))

(defmulti command-spec :cmd)

(s/def ::id ::us/string)
(s/def ::uri ::us/uri)
(s/def ::wait ::us/boolean)
(s/def ::cmd ::us/keyword)

(defmethod command-spec :export-shapes [_] ::export-shapes/params)
(defmethod command-spec :export-frames [_] ::export-frames/params)
(defmethod command-spec :get-resource [_] (s/keys :req-un [::id]))

(s/def ::params
  (s/and (s/keys :req-un [::cmd]
                 :opt-un [::wait ::uri])
         (s/multi-spec command-spec :cmd)))

(defn validate-uri!
  [uri]
  (let [white-list (cf/get :domain-white-list #{})
        default    (cf/get :public-uri)]
    (when-not (or (contains? white-list (u/get-domain uri))
                  (= (u/get-domain default) (u/get-domain uri)))
      (ex/raise :type :validation
                :code :domain-not-allowed
                :hint "looks like the uri provided is not part of the white list"))))

(defn handler
  [{:keys [:request/params] :as exchange}]
  (let [{:keys [cmd uri] :as params} (us/conform ::params params)]
    (l/debug :hint "process-request" :cmd cmd)
    (some-> uri validate-uri!)
    (case cmd
      :get-resource  (resources/handler exchange)
      :export-shapes (export-shapes/handler exchange params)
      :export-frames (export-frames/handler exchange params)
      (ex/raise :type :internal
                :code :method-not-implemented
                :hint (str/istr "method ~{cmd} not implemented")))))

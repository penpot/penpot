;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.handlers
  (:require
   [app.auth :as auth]
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.handlers.export :as export]
   [app.util.transit :as t]
   [clojure.spec.alpha :as s]
   [promesa.core :as p]))

(l/set-level! :debug)

(def ^:private error-codes
  #{:queue-full})

(defn on-error
  [error exchange]
  (let [{:keys [type code] :as data} (ex-data error)]
    (cond
      (and (= :validation type)
           (contains? error-codes code))
      (let [data {:type :validation
                  :code code
                  :hint (ex-message error)}]
        (l/warn :hint "rejecting export request" :code code)
        (-> exchange
            (assoc :response/status 429)
            (assoc :response/body (t/encode data))
            (assoc :response/headers {"content-type" "application/transit+json"})))

      (= :authentication type)
      (let [data {:type :authentication
                  :code code
                  :hint (ex-message error)}]
        (-> exchange
            (assoc :response/status 401)
            (assoc :response/body (t/encode data))
            (assoc :response/headers {"content-type" "application/transit+json"})))

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
                  :code code
                  :hint (ex-message error)
                  :data data}]
        (l/error :hint "unexpected internal error" :cause error)
        (-> exchange
            (assoc :response/status 500)
            (assoc :response/body (t/encode (d/without-nils data)))
            (assoc :response/headers {"content-type" "application/transit+json"}))))))

(defn handler
  "The original `POST /api/export` entry point, and the one the browser backend
  still goes through. The export runs as soon as it is asked for, and the
  contract is unchanged: `:wait` answers with the finished resource, otherwise
  with the resource handle while the work runs."
  [{:keys [:request/params :request/auth-token] :as exchange}]
  (let [{:keys [cmd wait] :as params} (export/conform-params params)]
    (l/debug :hint "process-request" :cmd cmd)
    (->> (auth/resolve-profile-id auth-token)
         (p/mcat (fn [profile-id]
                   ;; The session wins when there is one; the body value stays
                   ;; the fallback so nothing that used to work stops working.
                   (export/export! auth-token (cond-> params
                                                (some? profile-id)
                                                (assoc :profile-id profile-id)))))
         (p/mcat (fn [{:keys [resource pending]}]
                   (if wait
                     (p/fmap (fn [resource]
                               (assoc exchange :response/body resource))
                             pending)
                     (do
                       (p/merr (constantly nil) pending)
                       (p/resolved
                        (assoc exchange :response/body (dissoc resource :path))))))))))

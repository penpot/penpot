;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.loggers.activity
  "Activity registry logger consumer."
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.util.async :as aa]
   [app.util.http :as http]
   [app.util.logging :as l]
   [app.util.time :as dt]
   [app.util.transit :as t]
   [app.worker :as wrk]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [lambdaisland.uri :as u]))

(declare process-event)
(declare handle-event)

(s/def ::uri ::us/string)

(defmethod ig/pre-init-spec ::reporter [_]
  (s/keys :req-un [::wrk/executor]
          :opt-un [::uri]))

(defmethod ig/init-key ::reporter
  [_ {:keys [uri] :as cfg}]
  (if (string? uri)
    (do
      (l/info :msg "intializing activity reporter" :uri uri)
      (let [xform    (comp (map process-event)
                           (filter map?))
            input    (a/chan (a/sliding-buffer 1024) xform)]
        (a/go-loop []
          (when-let [event (a/<! input)]
            (a/<! (handle-event cfg event))
            (recur)))

        (fn [& [cmd & params]]
          (case cmd
            :stop (a/close! input)
            :submit (when-not (a/offer! input (first params))
                      (l/warn :msg "activity channel is full"))))))
    (constantly nil)))

(defmethod ig/halt-key! ::reporter
  [_ f]
  (f :stop))

(defn- clean-params
  "Cleans the params from complex data, only accept strings, numbers and
  uuids and removing sensitive data such as :password and related
  props."
  [params]
  (let [params (dissoc params :profile-id :session-id :password :old-password)]
    (reduce-kv (fn [params k v]
                 (cond-> params
                   (or (string? v)
                       (uuid? v)
                       (number? v))
                   (assoc k v)))
               {}
               params)))

(defn- process-event
  [{:keys [type name params result] :as event}]
  (let [profile-id (:profile-id params)]
    (if (uuid? profile-id)
      {:type (str "backend:" (d/name type))
       :name name
       :timestamp (dt/now)
       :profile-id profile-id
       :props (clean-params params)}
      (cond
        (= "register-profile" name)
        {:type (str "backend:" (d/name type))
         :name name
         :timestamp (dt/now)
         :profile-id (:id result)
         :props (clean-params (:props result))}

        :else nil))))

(defn- send-activity
  [{:keys [uri tokens]} event i]
  (try
    (let [token    (tokens :generate {:iss "authentication"
                                      :iat (dt/now)
                                      :uid (:profile-id event)})
          body     (t/encode {:events [event]})
          headers  {"content-type" "application/transit+json"
                    "origin" (cf/get :public-uri)
                    "cookie" (u/map->query-string {:auth-token token})}
          params   {:uri uri
                    :timeout 6000
                    :method :post
                    :headers headers
                    :body body}
          response (http/send! params)]
      (if (= (:status response) 204)
        true
        (do
          (l/error :hint "error on sending activity"
                   :try i
                   :rsp (pr-str response))
          false)))
    (catch Exception e
      (l/error :hint "error on sending message to loki"
               :cause e
               :try i)
      false)))

(defn- handle-event
  [{:keys [executor] :as cfg} event]
  (aa/with-thread executor
    (loop [i 1]
      (when (and (not (send-activity cfg event i)) (< i 20))
        (Thread/sleep (* i 2000))
        (recur (inc i))))))


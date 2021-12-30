;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.loggers.database
  "A specific logger impl that persists errors on the database."
  (:require
   [app.common.logging :as l]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.util.async :as aa]
   [app.worker :as wrk]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Error Listener
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare handle-event)

(defonce enabled (atom true))

(defn- persist-on-database!
  [{:keys [pool] :as cfg} {:keys [id] :as event}]
  (db/with-atomic [conn pool]
    (db/insert! conn :server-error-report
                {:id id :content (db/tjson event)})))

(defn- parse-event-data
  [event]
  (reduce-kv
   (fn [acc k v]
     (cond
       (= k :id)         (assoc acc k (uuid/uuid v))
       (= k :profile-id) (assoc acc k (uuid/uuid v))
       (str/blank? v)    acc
       :else             (assoc acc k v)))
   {}
   event))

(defn parse-event
  [event]
  (-> (parse-event-data event)
      (assoc :tenant (cf/get :tenant))
      (assoc :host (cf/get :host))
      (assoc :public-uri (cf/get :public-uri))
      (assoc :version (:full cf/version))
      (update :id (fn [id] (or id (uuid/next))))))

(defn handle-event
  [{:keys [executor] :as cfg} event]
  (aa/with-thread executor
    (try
      (let [event (parse-event event)
            uri   (cf/get :public-uri)]
        (l/debug :hint "registering error on database" :id (:id event)
                 :uri (str uri "/dbg/error/" (:id event)))
        (persist-on-database! cfg event))
      (catch Exception e
        (l/warn :hint "unexpected exception on database error logger"
                :cause e)))))

(defmethod ig/pre-init-spec ::reporter [_]
  (s/keys :req-un [::wrk/executor ::db/pool ::receiver]))

(defmethod ig/init-key ::reporter
  [_ {:keys [receiver] :as cfg}]
  (l/info :msg "initializing database error persistence")
  (let [output (a/chan (a/sliding-buffer 128)
                       (filter (fn [event]
                                 (= (:logger/level event) "error"))))]
    (receiver :sub output)
    (a/go-loop []
      (let [msg (a/<! output)]
        (if (nil? msg)
          (l/info :msg "stoping error reporting loop")
          (do
            (a/<! (handle-event cfg msg))
            (recur)))))
    output))

(defmethod ig/halt-key! ::reporter
  [_ output]
  (a/close! output))

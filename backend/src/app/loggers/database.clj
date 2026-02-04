;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.loggers.database
  "A specific logger impl that persists errors on the database."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.pprint :as pp]
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.db :as db]
   [app.loggers.audit :as audit]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [promesa.exec :as px]
   [promesa.exec.csp :as sp]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Error Listener
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare handle-event)

(defonce enabled (atom true))

(defn- persist-on-database!
  [pool id version report]
  (when-not (db/read-only? pool)
    (db/insert! pool :server-error-report
                {:id id
                 :version version
                 :content (db/tjson report)})))

(defn- concurrent-exception?
  [cause]
  (or (instance? java.util.concurrent.CompletionException cause)
      (instance? java.util.concurrent.ExecutionException cause)))

(defn record->report
  [{:keys [::l/context ::l/message ::l/props ::l/logger ::l/level ::l/cause] :as record}]
  (assert (l/valid-record? record) "expectd valid log record")
  (let [data (if (concurrent-exception? cause)
               (ex-data (ex-cause cause))
               (ex-data cause))

        ctx  (-> context
                 (assoc :service/tenant (cf/get :tenant))
                 (assoc :service/host (cf/get :host))
                 (assoc :service/public-uri (str (cf/get :public-uri)))
                 (assoc :backend/version (:full cf/version))
                 (assoc :logger/name logger)
                 (assoc :logger/level level)
                 (dissoc :request/params :value :params :data))]

    (merge
     {:context (-> (into (sorted-map) ctx)
                   (pp/pprint-str :length 50))
      :props   (pp/pprint-str props :length 50)
      :hint    (or (when-let [message (ex-message cause)]
                     (if-let [props-hint (:hint props)]
                       (str props-hint ": " message)
                       message))
                   @message)
      :trace   (or (::trace record)
                   (some-> cause (ex/format-throwable :data? true :explain? false :header? false :summary? false)))}

     (when-let [params (or (:request/params context) (:params context))]
       {:params (pp/pprint-str params :length 20 :level 20)})

     (when-let [value (:value context)]
       {:value (pp/pprint-str value :length 30 :level 13)})

     (when-let [data (some-> data (dissoc ::s/problems ::s/value ::s/spec ::sm/explain :hint))]
       {:data (pp/pprint-str data :length 30 :level 13)})

     (when-let [explain (ex/explain data :length 30 :level 13)]
       {:explain explain}))))

(defn- handle-log-record
  "Convert the log record into a report object and persist it on the database"
  [{:keys [::db/pool]} {:keys [::l/id] :as record}]
  (try
    (let [uri    (cf/get :public-uri)
          report (-> record record->report d/without-nils)]
      (l/dbg :hint "registering error on database"
             :id id
             :src "logging"
             :uri (str uri "/dbg/error/" id))
      (persist-on-database! pool id 3 report))
    (catch Throwable cause
      (l/warn :hint "unexpected exception on database error logger" :cause cause))))

(defn- event->report
  [{:keys [::audit/context ::audit/props ::audit/ip-addr] :as record}]
  (let [context
        (reduce-kv (fn [context k v]
                     (let [k' (keyword "frontend" (name k))]
                       (-> context
                           (dissoc k)
                           (assoc k' v))))
                   context
                   context)

        context
        (-> context
            (assoc :backend/tenant (cf/get :tenant))
            (assoc :backend/host (cf/get :host))
            (assoc :backend/public-uri (str (cf/get :public-uri)))
            (assoc :backend/version (:full cf/version))
            (assoc :frontend/ip-addr ip-addr))]

    {:context (-> (into (sorted-map) context)
                  (pp/pprint-str :length 50))
     :props   (pp/pprint-str props :length 50)
     :hint    (get props :hint)
     :report  (get props :report)}))

(defn- handle-audit-event
  "Convert the log record into a report object and persist it on the database"
  [{:keys [::db/pool]} {:keys [::audit/id] :as event}]
  (try
    (let [uri    (cf/get :public-uri)
          report (-> event event->report d/without-nils)]
      (l/dbg :hint "registering error on database"
             :id id
             :src "audit-log"
             :uri (str uri "/dbg/error/" id))
      (persist-on-database! pool id 4 report))
    (catch Throwable cause
      (l/warn :hint "unexpected exception on database error logger" :cause cause))))

(defmethod ig/assert-key ::reporter
  [_ params]
  (assert (db/pool? (::db/pool params)) "expect valid database pool"))

(defmethod ig/init-key ::reporter
  [_ cfg]
  (let [input  (sp/chan :buf (sp/sliding-buffer 256))
        thread (px/thread
                 {:name "penpot/reporter/database"}
                 (l/info :hint "initializing database error persistence")
                 (try
                   (loop []
                     (when-let [item (sp/take! input)]
                       (cond
                         (::l/id item)
                         (handle-log-record cfg item)

                         (::audit/id item)
                         (handle-audit-event cfg item)

                         :else
                         (l/warn :hint "received unexpected item" :item item))

                       (recur)))

                   (catch InterruptedException _
                     (l/debug :hint "reporter interrupted"))
                   (catch Throwable cause
                     (l/error :hint "unexpected error" :cause cause))
                   (finally
                     (l/info :hint "reporter terminated"))))]

    (add-watch l/log-record ::reporter
               (fn [_ _ _ record]
                 (when (= :error (::l/level record))
                   (sp/put! input record))))

    {::input input
     ::thread thread}))

(defmethod ig/halt-key! ::reporter
  [_ {:keys [::input ::thread]}]
  (remove-watch l/log-record ::reporter)
  (sp/close! input)
  (px/interrupt! thread))

(defn emit
  "Emit an event/report into the database reporter"
  [cfg event]
  (when-let [{:keys [::input]} (get cfg ::reporter)]
    (sp/put! input event)))


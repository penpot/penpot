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
  [pool id report]
  (when-not (db/read-only? pool)
    (db/insert! pool :server-error-report
                {:id id
                 :version 3
                 :content (db/tjson report)})))

(defn record->report
  [{:keys [::l/context ::l/message ::l/props ::l/logger ::l/level ::l/cause] :as record}]
  (assert (l/valid-record? record) "expectd valid log record")
  (if (or (instance? java.util.concurrent.CompletionException cause)
          (instance? java.util.concurrent.ExecutionException cause))
    (-> record
        (assoc ::trace (ex/format-throwable cause :data? false :explain? false :header? false :summary? false))
        (assoc ::l/cause (ex-cause cause))
        (record->report))

    (let [data (ex-data cause)
          ctx  (-> context
                   (assoc :tenant (cf/get :tenant))
                   (assoc :host (cf/get :host))
                   (assoc :public-uri (cf/get :public-uri))
                   (assoc :logger/name logger)
                   (assoc :logger/level level)
                   (dissoc :request/params :value :params :data))]
      (merge
       {:context (-> (into (sorted-map) ctx)
                     (pp/pprint-str :length 50))
        :props   (pp/pprint-str props :length 50)
        :hint    (or (ex-message cause) @message)
        :trace   (or (::trace record)
                     (some-> cause (ex/format-throwable :data? false :explain? false :header? false :summary? false)))}

       (when-let [params (or (:request/params context) (:params context))]
         {:params (pp/pprint-str params :length 30 :level 13)})

       (when-let [value (:value context)]
         {:value (pp/pprint-str value :length 30 :level 12)})

       (when-let [data (some-> data (dissoc ::s/problems ::s/value ::s/spec ::sm/explain :hint))]
         {:data (pp/pprint-str data :length 30 :level 12)})

       (when-let [explain (ex/explain data :length 30 :level 12)]
         {:explain explain})))))

(defn error-record?
  [{:keys [::l/level]}]
  (= :error level))

(defn- handle-event
  [{:keys [::db/pool]} {:keys [::l/id] :as record}]
  (try
    (let [uri    (cf/get :public-uri)
          report (-> record record->report d/without-nils)]
      (l/debug :hint "registering error on database" :id id
               :uri (str uri "/dbg/error/" id))

      (persist-on-database! pool id report))
    (catch Throwable cause
      (l/warn :hint "unexpected exception on database error logger" :cause cause))))

(defmethod ig/assert-key ::reporter
  [_ params]
  (assert (db/pool? (::db/pool params)) "expect valid database pool"))

(defmethod ig/init-key ::reporter
  [_ cfg]
  (let [input (sp/chan :buf (sp/sliding-buffer 64)
                       :xf  (filter error-record?))]
    (add-watch l/log-record ::reporter #(sp/put! input %4))

    (px/thread {:name "penpot/database-reporter"}
      (l/info :hint "initializing database error persistence")
      (try
        (loop []
          (when-let [record (sp/take! input)]
            (handle-event cfg record)
            (recur)))
        (catch InterruptedException _
          (l/debug :hint "reporter interrupted"))
        (catch Throwable cause
          (l/error :hint "unexpected error" :cause cause))
        (finally
          (sp/close! input)
          (remove-watch l/log-record ::reporter)
          (l/info :hint "reporter terminated"))))))

(defmethod ig/halt-key! ::reporter
  [_ thread]
  (some-> thread px/interrupt!))

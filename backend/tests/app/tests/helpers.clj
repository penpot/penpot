;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.tests.helpers
  (:require
   [expound.alpha :as expound]
   [app.common.pages :as cp]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.main :as main]
   [app.media-storage]
   [app.media]
   [app.migrations]
   [app.rpc.mutations.files :as files]
   [app.rpc.mutations.profile :as profile]
   [app.rpc.mutations.projects :as projects]
   [app.rpc.mutations.teams :as teams]
   [app.util.blob :as blob]
   [app.util.storage :as ust]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [datoteka.core :as fs]
   [environ.core :refer [env]]
   [integrant.core :as ig]
   [promesa.core :as p])
  (:import org.postgresql.ds.PGSimpleDataSource))

(def ^:dynamic *system* nil)
(def ^:dynamic *pool* nil)

(defn state-init
  [next]
  (let [config (-> (main/build-system-config cfg/test-config)
                   (dissoc :app.srepl/server
                           :app.http/server
                           :app.http/router
                           :app.notifications/handler
                           :app.http.auth/google
                           :app.http.auth/gitlab
                           :app.worker/scheduler
                           :app.worker/executor
                           :app.worker/worker))
        _      (ig/load-namespaces config)
        system (-> (ig/prep config)
                   (ig/init))]
    (try
      (binding [*system* system
                *pool*   (:app.db/pool system)]
        (next))
      (finally
        (ig/halt! system)))))

(defn database-reset
  [next]
  (let [sql (str "SELECT table_name "
                 "  FROM information_schema.tables "
                 " WHERE table_schema = 'public' "
                 "   AND table_name != 'migrations';")]
    (db/with-atomic [conn *pool*]
      (let [result (->> (db/exec! conn [sql])
                        (map :table-name))]
        (db/exec! conn [(str "TRUNCATE "
                             (apply str (interpose ", " result))
                             " CASCADE;")]))))
  (try
    (next)
    (finally
      (ust/clear! (:app.media-storage/storage *system*)))))

(defn mk-uuid
  [prefix & args]
  (uuid/namespaced uuid/zero (apply str prefix args)))


(defn create-profile
  [conn i]
  (let [params {:id (mk-uuid "profile" i)
                :fullname (str "Profile " i)
                :email (str "profile" i ".test@nodomain.com")
                :password "123123"
                :demo? true}]
    (->> (#'profile/create-profile conn params)
         (#'profile/create-profile-relations conn))))

(defn create-team
  [conn profile-id i]
  (let [id (mk-uuid "team" i)
        team (#'teams/create-team conn {:id id
                                        :profile-id profile-id
                                        :name (str "team" i)})]
    (#'teams/create-team-profile conn
                                 {:team-id id
                                  :profile-id profile-id
                                  :is-owner true
                                  :is-admin true
                                  :can-edit true})
    team))

(defn create-project
  [conn profile-id team-id i]
  (#'projects/create-project conn {:id (mk-uuid "project" i)
                                   :profile-id profile-id
                                   :team-id team-id
                                   :name (str "project" i)}))

(defn create-file
  [conn profile-id project-id is-shared i]
  (#'files/create-file conn {:id (mk-uuid "file" i)
                             :profile-id profile-id
                             :project-id project-id
                             :is-shared is-shared
                             :name (str "file" i)}))

(defn handle-error
  [^Throwable err]
  (if (instance? java.util.concurrent.ExecutionException err)
    (handle-error (.getCause err))
    err))

(defmacro try-on
  [expr]
  `(try
     (let [result# (deref ~expr)]
       [nil result#])
     (catch Exception e#
       [(handle-error e#) nil])))

(defmacro try-on!
  [expr]
  `(try
     {:error nil
      :result ~expr}
     (catch Exception e#
       {:error (handle-error e#)
        :result nil})))

(defmacro try!
  [expr]
  `(try
     {:error nil
      :result ~expr}
     (catch Exception e#
       {:error (handle-error e#)
        :result nil})))


(defn mutation!
  [{:keys [::type] :as data}]
  (let [method-fn (get-in *system* [:app.rpc/rpc :methods :mutation type])]
    (try-on!
     (method-fn (dissoc data ::type)))))

(defn query!
  [{:keys [::type] :as data}]
  (let [method-fn (get-in *system* [:app.rpc/rpc :methods :query type])]
    (try-on!
     (method-fn (dissoc data ::type)))))

;; --- Utils

(defn print-error!
  [error]
  (let [data (ex-data error)]
    (cond
      (= :spec-validation (:code data))
      (expound/printer (:data data))

      (= :service-error (:type data))
      (print-error! (.getCause ^Throwable error))

      :else
      (.printStackTrace ^Throwable error))))

(defn print-result!
  [{:keys [error result]}]
  (if error
    (do
      (println "====> START ERROR")
      (print-error! error)
      (println "====> END ERROR"))
    (do
      (println "====> START RESPONSE")
      (prn result)
      (println "====> END RESPONSE"))))

(defn exception?
  [v]
  (instance? Throwable v))

(defn ex-info?
  [v]
  (instance? clojure.lang.ExceptionInfo v))

(defn ex-of-type?
  [e type]
  (let [data (ex-data e)]
    (= type (:type data))))

(defn ex-of-code?
  [e code]
  (let [data (ex-data e)]
    (= code (:code data))))

(defn ex-with-code?
  [e code]
  (let [data (ex-data e)]
    (= code (:code data))))

(defn tempfile
  [source]
  (let [rsc (io/resource source)
        tmp (fs/create-tempfile)]
    (io/copy (io/file rsc)
             (io/file tmp))
    tmp))

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
   [app.common.data :as d]
   [app.common.pages :as cp]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.main :as main]
   [app.media]
   [app.migrations]
   [app.rpc.mutations.files :as files]
   [app.rpc.mutations.profile :as profile]
   [app.rpc.mutations.projects :as projects]
   [app.rpc.mutations.teams :as teams]
   [app.util.blob :as blob]
   [app.util.time :as dt]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [datoteka.core :as fs]
   [environ.core :refer [env]]
   [expound.alpha :as expound]
   [integrant.core :as ig]
   [mockery.core :as mk]
   [promesa.core :as p])
  (:import org.postgresql.ds.PGSimpleDataSource))

(def ^:dynamic *system* nil)
(def ^:dynamic *pool* nil)

(def config
  (merge {:redis-uri "redis://redis/1"
          :database-uri "postgresql://postgres/penpot_test"
          :storage-fs-directory "/tmp/app/storage"
          :migrations-verbose false}
         cfg/config))


(defn state-init
  [next]
  (let [config (-> (main/build-system-config config)
                   (dissoc :app.srepl/server
                           :app.http/server
                           :app.http/router
                           :app.notifications/handler
                           :app.http.auth/google
                           :app.http.auth/gitlab
                           :app.worker/scheduler
                           :app.worker/worker)
                   (d/deep-merge
                    {:app.storage/storage {:backend :tmp}
                     :app.tasks.file-media-gc/handler {:max-age (dt/duration 300)}}))
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
  (next))

(defn clean-storage
  [next]
  (let [path (fs/path "/tmp/penpot")]
    (when (fs/exists? path)
      (fs/delete (fs/path "/tmp/penpot")))
    (next)))

(defn serial
  [& funcs]
  (fn [next]
    (loop [f   (first funcs)
           fs  (rest funcs)]
      (when f
        (let [prm (promise)]
          (f #(deliver prm true))
          (deref prm)
          (recur (first fs)
                 (rest fs)))))
    (next)))

(defn mk-uuid
  [prefix & args]
  (uuid/namespaced uuid/zero (apply str prefix args)))


(defn create-profile
  [conn i]
  (let [params {:id (mk-uuid "profile" i)
                :fullname (str "Profile " i)
                :email (str "profile" i ".test@nodomain.com")
                :password "123123"
                :is-demo true}]
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


;; --- NEW HELPERS

(defn create-profile*
  ([i] (create-profile* *pool* i {}))
  ([i params] (create-profile* *pool* i params))
  ([conn i params]
   (let [params (merge {:id (mk-uuid "profile" i)
                        :fullname (str "Profile " i)
                        :email (str "profile" i ".test@nodomain.com")
                        :password "123123"
                        :is-demo false}
                       params)]
     (->> (#'profile/create-profile conn params)
          (#'profile/create-profile-relations conn)))))

(defn create-project*
  ([i params] (create-project* *pool* i params))
  ([conn i {:keys [profile-id team-id] :as params}]
   (us/assert uuid? profile-id)
   (us/assert uuid? team-id)
   (->> (merge {:id (mk-uuid "project" i)
                :name (str "project" i)}
               params)
        (#'projects/create-project conn))))

(defn create-file*
  ([i params]
   (create-file* *pool* i params))
  ([conn i {:keys [profile-id project-id] :as params}]
   (us/assert uuid? profile-id)
   (us/assert uuid? project-id)
   (#'files/create-file conn
                        (merge {:id (mk-uuid "file" i)
                                :name (str "file" i)}
                               params))))


(defn create-team*
  ([i params] (create-team* *pool* i params))
  ([conn i {:keys [profile-id] :as params}]
   (us/assert uuid? profile-id)
   (let [id   (mk-uuid "team" i)
         team (#'teams/create-team conn {:id id
                                         :profile-id profile-id
                                         :name (str "team" i)})]
     (#'teams/create-team-profile conn
                                  {:team-id id
                                   :profile-id profile-id
                                   :is-owner true
                                   :is-admin true
                                   :can-edit true})
     team)))


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

(defn sleep
  [ms]
  (Thread/sleep ms))

(defn mock-config-get-with
  "Helper for mock app.config/get"
  [data]
  (fn
    ([key] (get (merge config data) key))
    ([key default] (get (merge config data) key default))))

(defn create-complaint-for
  [conn {:keys [id created-at type]}]
  (db/insert! conn :profile-complaint-report
              {:profile-id id
               :created-at (or created-at (dt/now))
               :type (name type)
               :content (db/tjson {})}))

(defn create-global-complaint-for
  [conn {:keys [email type created-at]}]
  (db/insert! conn :global-complaint-report
              {:email email
               :type (name type)
               :created-at (or created-at (dt/now))
               :content (db/tjson {})}))


(defn reset-mock!
  [m]
  (reset! m @(mk/make-mock {})))

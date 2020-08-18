(ns app.tests.helpers
  (:require
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [datoteka.core :as fs]
   [cuerdas.core :as str]
   [mount.core :as mount]
   [environ.core :refer [env]]
   [app.common.pages :as cp]
   [app.services.init]
   [app.services.mutations.profile :as profile]
   [app.services.mutations.projects :as projects]
   [app.services.mutations.teams :as teams]
   [app.services.mutations.files :as files]
   [app.services.mutations.pages :as pages]
   [app.services.mutations.colors :as colors]
   [app.migrations]
   [app.media]
   [app.media-storage]
   [app.db :as db]
   [app.util.blob :as blob]
   [app.common.uuid :as uuid]
   [app.util.storage :as ust]
   [app.config :as cfg])
  (:import org.postgresql.ds.PGSimpleDataSource))

(defn testing-datasource
  []
  (doto (PGSimpleDataSource.)
    (.setServerName "postgres")
    (.setDatabaseName "app_test")
    (.setUser "app")
    (.setPassword "app")))

(defn state-init
  [next]
  (let [config (cfg/read-test-config env)]
    (try
      (let [pool (testing-datasource)]
        (-> (mount/only #{#'app.config/config
                          #'app.db/pool
                          #'app.redis/client
                          #'app.redis/conn
                          #'app.media/semaphore
                          #'app.services.init/query-services
                          #'app.services.init/mutation-services
                          #'app.migrations/migrations
                          #'app.media-storage/assets-storage
                          #'app.media-storage/media-storage})
            (mount/swap {#'app.config/config config
                         #'app.db/pool pool})
            (mount/start)))
      (next)
      (finally
        (mount/stop)))))

(defn database-reset
  [next]
  (let [sql (str "SELECT table_name "
                 "  FROM information_schema.tables "
                 " WHERE table_schema = 'public' "
                 "   AND table_name != 'migrations';")]
    (db/with-atomic [conn db/pool]
      (let [result (->> (db/exec! conn [sql])
                        (map :table-name))]
        (db/exec! conn [(str "TRUNCATE "
                             (apply str (interpose ", " result))
                             " CASCADE;")]))))
  (try
    (next)
    (finally
      (ust/clear! app.media-storage/media-storage)
      (ust/clear! app.media-storage/assets-storage))))

(defn mk-uuid
  [prefix & args]
  (uuid/namespaced uuid/zero (apply str prefix args)))

;; --- Profile creation

(defn create-profile
  [conn i]
  (let [params {:id (mk-uuid "profile" i)
                :fullname (str "Profile " i)
                :email (str "profile" i ".test@nodomain.com")
                :password "123123"}]
    (->> (#'profile/create-profile conn params)
         (#'profile/create-profile-relations conn))))

(defn create-team
  [conn profile-id i]
  (#'teams/create-team conn {:id (mk-uuid "team" i)
                             :profile-id profile-id
                             :name (str "team" i)}))

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

(defn create-page
  [conn profile-id file-id i]
  (#'pages/create-page conn {:id (mk-uuid "page" i)
                             :profile-id profile-id
                             :file-id file-id
                             :name (str "page" i)
                             :ordering i
                             :data cp/default-page-data}))

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

(defn print-error!
  [error]
  (let [data (ex-data error)]
    (cond
      (= :spec-validation (:code data))
      (println (:explain data))

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

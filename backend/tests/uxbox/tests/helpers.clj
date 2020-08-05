(ns uxbox.tests.helpers
  (:require
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [datoteka.core :as fs]
   [cuerdas.core :as str]
   [mount.core :as mount]
   [environ.core :refer [env]]
   [uxbox.common.pages :as cp]
   [uxbox.services.init]
   [uxbox.services.mutations.profile :as profile]
   [uxbox.services.mutations.projects :as projects]
   [uxbox.services.mutations.teams :as teams]
   [uxbox.services.mutations.files :as files]
   [uxbox.services.mutations.pages :as pages]
   ;; [uxbox.services.mutations.icons :as icons]
   [uxbox.services.mutations.colors :as colors]
   [uxbox.fixtures :as fixtures]
   [uxbox.migrations]
   [uxbox.media]
   [uxbox.media-storage]
   [uxbox.db :as db]
   [uxbox.util.blob :as blob]
   [uxbox.common.uuid :as uuid]
   [uxbox.util.storage :as ust]
   [uxbox.config :as cfg])
  (:import org.postgresql.ds.PGSimpleDataSource))

(defn testing-datasource
  []
  (doto (PGSimpleDataSource.)
    (.setServerName "postgres")
    (.setDatabaseName "uxbox_test")
    (.setUser "uxbox")
    (.setPassword "uxbox")))

(defn state-init
  [next]
  (let [config (cfg/read-test-config env)]
    (try
      (let [pool (testing-datasource)]
        (-> (mount/only #{#'uxbox.config/config
                          #'uxbox.db/pool
                          #'uxbox.redis/client
                          #'uxbox.redis/conn
                          #'uxbox.media/semaphore
                          #'uxbox.services.init/query-services
                          #'uxbox.services.init/mutation-services
                          #'uxbox.migrations/migrations
                          #'uxbox.media-storage/assets-storage
                          #'uxbox.media-storage/media-storage})
            (mount/swap {#'uxbox.config/config config
                         #'uxbox.db/pool pool})
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
      (ust/clear! uxbox.media-storage/media-storage)
      (ust/clear! uxbox.media-storage/assets-storage))))

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

;; (defn create-image-library
;;   [conn team-id i]
;;   (#'images/create-library conn {:id (mk-uuid "imgcoll" i)
;;                                  :team-id team-id
;;                                  :name (str "image library " i)}))
;;
;; (defn create-icon-library
;;   [conn team-id i]
;;   (#'icons/create-library conn {:id (mk-uuid "imgcoll" i)
;;                                 :team-id team-id
;;                                 :name (str "icon library " i)}))
;; (defn create-color-library
;;   [conn team-id i]
;;   (#'colors/create-library conn {:id (mk-uuid "imgcoll" i)
;;                                  :team-id team-id
;;                                  :name (str "color library " i)}))

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

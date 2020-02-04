(ns uxbox.tests.helpers
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [cuerdas.core :as str]
   [mount.core :as mount]
   [environ.core :refer [env]]
   [uxbox.services.mutations.profile :as profile]
   [uxbox.services.mutations.projects :as projects]
   [uxbox.services.mutations.project-files :as files]
   [uxbox.services.mutations.project-pages :as pages]
   [uxbox.services.mutations.images :as images]
   [uxbox.fixtures :as fixtures]
   [uxbox.migrations]
   [uxbox.media]
   [uxbox.db :as db]
   [uxbox.util.blob :as blob]
   [uxbox.util.uuid :as uuid]
   [uxbox.util.storage :as ust]
   [uxbox.config :as cfg]))

(defn state-init
  [next]
  (let [config (cfg/read-test-config env)]
    (-> (mount/only #{#'uxbox.config/config
                      #'uxbox.core/system
                      #'uxbox.db/pool
                      #'uxbox.services.init/query-services
                      #'uxbox.services.init/mutation-services
                      #'uxbox.migrations/migrations
                      #'uxbox.media/assets-storage
                      #'uxbox.media/media-storage})
        (mount/swap {#'uxbox.config/config config})
        (mount/start))
    (try
      (next)
      (finally
        (mount/stop)))))

(defn database-reset
  [next]
  (let [sql (str "SELECT table_name "
                 "  FROM information_schema.tables "
                 " WHERE table_schema = 'public' "
                 "   AND table_name != 'migrations';")]

    @(db/with-atomic [conn db/pool]
       (-> (db/query conn sql)
           (p/then #(map :table-name %))
           (p/then (fn [result]
                     (db/query-one conn (str "TRUNCATE "
                                             (apply str (interpose ", " result))
                                             " CASCADE;")))))))
  (try
    (next)
    (finally
      (ust/clear! uxbox.media/media-storage)
      (ust/clear! uxbox.media/assets-storage))))

(defn mk-uuid
  [prefix & args]
  (uuid/namespaced uuid/oid (apply str prefix args)))

;; --- Profile creation

(defn create-user
  [conn i]
  (profile/create-profile conn {:id (mk-uuid "user" i)
                                :fullname (str "User " i)
                                :email (str "user" i ".test@nodomain.com")
                                :password "123123"
                                :metadata {}}))

(defn create-project
  [conn user-id i]
  (projects/create-project conn {:id (mk-uuid "project" i)
                                 :user user-id
                                 :version 1
                                 :name (str "sample project " i)}))


(defn create-project-file
  [conn user-id project-id i]
  (files/create-file conn {:id (mk-uuid "project-file" i)
                           :user user-id
                           :project-id project-id
                           :name (str "sample project file" i)}))


(defn create-project-page
  [conn user-id file-id i]
  (pages/create-page conn {:id (mk-uuid "page" i)
                           :user user-id
                           :file-id file-id
                           :name (str "page" i)
                           :ordering i
                           :data {:version 1
                                  :shapes []
                                  :options {}
                                  :canvas []
                                  :shapes-by-id {}}}))

(defn create-images-collection
  [conn user-id i]
  (images/create-images-collection conn {:id (mk-uuid "imgcoll" i)
                                         :user user-id
                                         :name (str "image collection " i)}))

(defn handle-error
  [err]
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
     (let [result# (deref ~expr)]
       {:error nil
        :result result#})
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

      :else
      (.printStackTrace error))))

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

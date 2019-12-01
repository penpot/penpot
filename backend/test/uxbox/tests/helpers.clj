(ns uxbox.tests.helpers
  (:require
   [clojure.spec.alpha :as s]
   [buddy.hashers :as hashers]
   [promesa.core :as p]
   [cuerdas.core :as str]
   [mount.core :as mount]
   [datoteka.storages :as st]
   [uxbox.services.mutations.profiles :as profiles]
   [uxbox.services.mutations.projects :as projects]
   [uxbox.services.mutations.pages :as pages]
   [uxbox.fixtures :as fixtures]
   [uxbox.migrations]
   [uxbox.media]
   [uxbox.db :as db]
   [uxbox.util.blob :as blob]
   [uxbox.util.uuid :as uuid]
   [uxbox.config :as cfg]))

(defn state-init
  [next]
  (let [config (cfg/read-test-config)]
    (-> (mount/only #{#'uxbox.config/config
                      #'uxbox.config/secret
                      #'uxbox.core/system
                      #'uxbox.db/pool
                      #'uxbox.migrations/migrations
                      #'uxbox.media/assets-storage
                      #'uxbox.media/media-storage
                      #'uxbox.media/images-storage
                      #'uxbox.media/thumbnails-storage})
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
      (st/clear! uxbox.media/media-storage)
      (st/clear! uxbox.media/assets-storage))))

(defn mk-uuid
  [prefix & args]
  (uuid/namespaced uuid/oid (apply str prefix args)))

;; --- Users creation

(defn create-user
  [conn i]
  (profiles/create-profile conn {:id (mk-uuid "user" i)
                                 :fullname (str "User " i)
                                 :username (str "user" i)
                                 :email (str "user" i ".test@uxbox.io")
                                 :password "123123"
                                 :metadata {}}))

(defn create-project
  [conn user-id i]
  (projects/create-project conn {:id (mk-uuid "project" i)
                                 :user user-id
                                 :name (str "sample project " i)}))

(defn create-page
  [conn uid pid i]
  (pages/create-page conn {:id (mk-uuid "page" i)
                           :user uid
                           :project-id pid
                           :name (str "page" i)
                           :data {:shapes []}
                           :metadata {}}))

(defn handle-error
  [err]
  (cond
    (instance? clojure.lang.ExceptionInfo err)
    (ex-data err)

    (instance? java.util.concurrent.ExecutionException err)
    (handle-error (.getCause err))

    :else
    [err nil]))

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

(defn print-result!
  [{:keys [error result]}]
  (if error
    (do
      (println "====> START ERROR")
      (if (= :spec-validation (:code error))
        (s/explain-out (:data error))
        (prn error))
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

(defn ex-with-code?
  [e code]
  (let [data (ex-data e)]
    (= code (:code data))))

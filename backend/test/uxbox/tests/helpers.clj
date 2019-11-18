(ns uxbox.tests.helpers
  (:require
   [clojure.spec.alpha :as s]
   [buddy.hashers :as hashers]
   [promesa.core :as p]
   [cuerdas.core :as str]
   [mount.core :as mount]
   [datoteka.storages :as st]
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
                      ;; #'uxbox.db/datasource
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

(declare decode-user-row)
(declare decode-page-row)

(defn create-user
  [conn i]
  (let [sql "insert into users (id, fullname, username, email, password, metadata, photo)
             values ($1, $2, $3, $4, $5, $6, '') returning *"]
    (-> (db/query-one conn [sql
                            (mk-uuid "user" i)
                            (str "User " i)
                            (str "user" i)
                            (str "user" i ".test@uxbox.io")
                            (hashers/encrypt "123123")
                            (blob/encode {})])
        (p/then' decode-user-row))))

(defn create-project
  [conn uid i]
  (let [sql "insert into projects (id, user_id, name)
             values ($1, $2, $3) returning *"
        name (str "sample project " i)]
    (db/query-one conn [sql (mk-uuid "project" i) uid name])))

(defn create-page
  [conn uid pid i]
  (let [sql "insert into pages (id, user_id, project_id, name, data, metadata)
             values ($1, $2, $3, $4, $5, $6) returning *"
        data (blob/encode {:shapes []})
        mdata (blob/encode {})
        name (str "page" i)
        id (mk-uuid "page" i)]
    (-> (db/query-one conn [sql id uid pid name data mdata])
        (p/then' decode-page-row))))

(defn- decode-page-row
  [{:keys [data metadata] :as row}]
  (when row
    (cond-> row
      data (assoc :data (blob/decode data))
      metadata (assoc :metadata (blob/decode metadata)))))

(defn- decode-user-row
  [{:keys [metadata] :as row}]
  (when row
    (cond-> row
      metadata (assoc :metadata (blob/decode metadata)))))

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

(defn print-result!
  [{:keys [error result]}]

  (if error
    (do
      (println "====> START ERROR")
      (if (= :spec-validation (:code error))
        (do
          (s/explain-out (:data error))
          (println "====> END ERROR"))
        (prn error)))
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

(ns app.srepl.main
  "A  main namespace for server repl."
  #_:clj-kondo/ignore
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.pages :as cp]
   [app.common.pages.migrations :as pmg]
   [app.common.spec.file :as spec.file]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.main :refer [system]]
   [app.rpc.queries.profile :as prof]
   [app.srepl.dev :as dev]
   [app.util.blob :as blob]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [clojure.walk :as walk]
   [cuerdas.core :as str]
   [expound.alpha :as expound]
   [fipp.edn :refer [pprint]]))

(defn update-file
  ([system id f] (update-file system id f false))
  ([system id f save?]
   (db/with-atomic [conn (:app.db/pool system)]
     (let [file (db/get-by-id conn :file id {:for-update true})
           file (-> file
                    (update :data app.util.blob/decode)
                    (update :data pmg/migrate-data)
                    (update :data f)
                    (update :data blob/encode)
                    (update :revn inc))]
       (when save?
         (db/update! conn :file
                     {:data (:data file)}
                     {:id (:id file)}))
       (update file :data blob/decode)))))

(defn reset-file-data
  [system id data]
  (db/with-atomic [conn (:app.db/pool system)]
    (db/update! conn :file
                {:data data}
                {:id id})))

(defn get-file
  [system id]
  (-> (:app.db/pool system)
      (db/get-by-id :file id)
      (update :data app.util.blob/decode)
      (update :data pmg/migrate-data)))

(defn duplicate-file
  "This is a raw version of duplication of file just only for forensic analysis"
  [system file-id email]
  (db/with-atomic [conn (:app.db/pool system)]
    (when-let [profile (some->> (prof/retrieve-profile-data-by-email conn (str/lower email))
                                (prof/populate-additional-data conn))]
      (when-let [file (db/exec-one! conn (sql/select :file {:id file-id}))]
        (let [params (assoc file
                            :id (uuid/next)
                            :project-id (:default-project-id profile))]
          (db/insert! conn :file params)
          (:id file))))))

;; (defn check-image-shapes
;;   [{:keys [data] :as file} stats]
;;   (println "=> analizing file:" (:name file) (:id file))
;;   (swap! stats update :total-files (fnil inc 0))
;;   (let [affected? (atom false)]
;;     (walk/prewalk (fn [obj]
;;                     (when (and (map? obj) (= :image (:type obj)))
;;                       (when-let [fcolor (some-> obj :fill-color str/upper)]
;;                         (when (or (= fcolor "#B1B2B5")
;;                                   (= fcolor "#7B7D85"))
;;                           (reset! affected? true)
;;                           (swap! stats update :affected-shapes (fnil inc 0))
;;                           (println "--> image shape:" ((juxt :id :name :fill-color :fill-opacity) obj)))))
;;                     obj)
;;                   data)
;;     (when @affected?
;;       (swap! stats update :affected-files (fnil inc 0)))))

(defn analyze-files
  [system {:keys [sleep chunk-size max-chunks on-file]
           :or {sleep 1000 chunk-size 10 max-chunks ##Inf}}]
  (let [stats (atom {})]
    (letfn [(retrieve-chunk [conn cursor]
              (let [sql (str "select id, name, modified_at, data from file "
                             " where modified_at < ? and deleted_at is null "
                             " order by modified_at desc limit ?")]
                (->> (db/exec! conn [sql cursor chunk-size])
                     (map #(update % :data blob/decode)))))

            (process-chunk [chunk]
              (loop [items chunk]
                (when-let [item (first items)]
                  (on-file item stats)
                  (recur (rest items)))))]

      (db/with-atomic [conn (:app.db/pool system)]
        (loop [cursor (dt/now)
               chunks 0]
          (when (< chunks max-chunks)
            (when-let [chunk (retrieve-chunk conn cursor)]
              (let [cursor (-> chunk last :modified-at)]
                (process-chunk chunk)
                (Thread/sleep (inst-ms (dt/duration sleep)))
                (recur cursor (inc chunks))))))
        @stats))))

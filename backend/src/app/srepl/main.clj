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
   [fipp.edn :refer [pprint]]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [expound.alpha :as expound]))

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

(defn verify-files
  [system {:keys [age sleep chunk-size max-chunks stop-on-error? verbose?]
           :or {sleep 1000
                age "72h"
                chunk-size 10
                verbose? false
                stop-on-error? true
                max-chunks ##Inf}}]

  (letfn [(retrieve-chunk [conn cursor]
            (let [sql (str "select id, name, modified_at, data from file "
                           " where modified_at > ? and deleted_at is null "
                           " order by modified_at asc limit ?")
                  age (if cursor
                        cursor
                        (-> (dt/now) (dt/minus age)))]
              (seq (db/exec! conn [sql age chunk-size]))))

          (validate-item [{:keys [id data modified-at] :as file}]
            (let [data   (blob/decode data)
                  valid? (s/valid? ::spec.file/data data)]

              (l/debug :hint "validated file"
                       :file-id id
                       :age (-> (dt/diff modified-at (dt/now))
                                (dt/truncate :minutes)
                                (str)
                                (subs 2)
                                (str/lower))
                       :valid valid?)

              (when (and (not valid?) verbose?)
                (let [edata (-> (s/explain-data ::spec.file/data data)
                                (update ::s/problems #(take 5 %)))]
                  (binding [s/*explain-out* expound/printer]
                    (l/warn ::l/raw (with-out-str (s/explain-out edata))))))

              (when (and (not valid?) stop-on-error?)
                (throw (ex-info "penpot/abort" {})))

              valid?))

          (validate-chunk [chunk]
            (loop [items   chunk
                   success 0
                   errored 0]

              (if-let [item (first items)]
                (if (validate-item item)
                  (recur (rest items) (inc success) errored)
                  (recur (rest items) success (inc errored)))
                [(:modified-at (last chunk))
                 success
                 errored])))

          (fmt-result [ns ne]
            {:total (+ ns ne)
             :errors ne
             :success ns})

          ]

    (try
      (db/with-atomic [conn (:app.db/pool system)]
        (loop [cursor  nil
               chunks  0
               success 0
               errors 0]
          (if (< chunks max-chunks)
            (if-let [chunk (retrieve-chunk conn cursor)]
              (let [[cursor success' errors'] (validate-chunk chunk)]
                (Thread/sleep (inst-ms (dt/duration sleep)))
                (recur cursor
                       (inc chunks)
                       (+ success success')
                       (+ errors errors')))
              (fmt-result success errors))
            (fmt-result success errors))))
      (catch Throwable cause
        (when (not= "penpot/abort" (ex-message cause))
          (throw cause))
        :error))))


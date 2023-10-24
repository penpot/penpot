;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.helpers
  "A  main namespace for server repl."
  (:refer-clojure :exclude [parse-uuid])
  #_:clj-kondo/ignore
  (:require
   [app.auth :refer [derive-password]]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.migrations :as pmg]
   [app.common.logging :as l]
   [app.common.pages :as cp]
   [app.common.pprint :refer [pprint]]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.main :refer [system]]
   [app.rpc.commands.files :as files]
   [app.util.blob :as blob]
   [app.util.objects-map :as omap]
   [app.util.pointer-map :as pmap]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [clojure.stacktrace :as strace]
   [clojure.walk :as walk]
   [cuerdas.core :as str]
   [expound.alpha :as expound]
   [promesa.core :as p]
   [promesa.exec :as px]
   [promesa.exec.csp :as sp]))

(def ^:dynamic *conn* nil)

(defn println!
  [& params]
  (locking println
    (apply println params)))

(defn parse-uuid
  [v]
  (if (uuid? v)
    v
    (d/parse-uuid v)))

(defn resolve-connectable
  [o]
  (if (db/connection? o)
    o
    (if (db/pool? o)
      o
      (or (::db/conn o)
          (::db/pool o)))))

(defn reset-password!
  "Reset a password to a specific one for a concrete user or all users
  if email is `:all` keyword."
  [system & {:keys [email password] :or {password "123123"} :as params}]
  (us/verify! (contains? params :email) "`email` parameter is mandatory")
  (db/with-atomic [conn (:app.db/pool system)]
    (let [password (derive-password password)]
      (if (= email :all)
        (db/exec! conn ["update profile set password=?" password])
        (let [email (str/lower email)]
          (db/exec! conn ["update profile set password=? where email=?" password email]))))))

(defn reset-file-data!
  "Hardcode replace of the data of one file."
  [system id data]
  (db/with-atomic [conn (:app.db/pool system)]
    (db/update! conn :file
                {:data data}
                {:id id})))

(defn get-file
  "Get the migrated data of one file."
  [system id]
  (db/with-atomic [conn (:app.db/pool system)]
    (binding [pmap/*load-fn* (partial files/load-pointer conn id)]
      (-> (db/get-by-id conn :file id)
          (update :data blob/decode)
          (update :data pmg/migrate-data)
          (files/process-pointers deref)))))

(defn update-file!
  "Apply a function to the data of one file. Optionally save the changes or not.
  The function receives the decoded and migrated file data."
  [system & {:keys [update-fn id save? migrate? inc-revn?]
             :or {save? false migrate? true inc-revn? true}}]
  (db/with-atomic [conn (:app.db/pool system)]
    (let [file (-> (db/get-by-id conn :file id {::db/for-update? true})
                   (update :features db/decode-pgarray #{}))]
      (binding [*conn* conn
                pmap/*tracked* (atom {})
                pmap/*load-fn* (partial files/load-pointer conn id)
                cfeat/*wrap-with-pointer-map-fn*
                (if (contains? (:features file) "fdata/pointer-map") pmap/wrap identity)
                cfeat/*wrap-with-objects-map-fn*
                (if (contains? (:features file) "fdata/objectd-map") omap/wrap identity)]
        (let [file (-> file
                       (update :data blob/decode)
                       (cond-> migrate? (update :data pmg/migrate-data))
                       (update-fn)
                       (cond-> inc-revn? (update :revn inc)))]
          (when save?
            (let [features (db/create-array conn "text" (:features file))
                  data     (blob/encode (:data file))]
              (db/update! conn :file
                          {:data data
                           :revn (:revn file)
                           :features features}
                          {:id id})

              (when (contains? (:features file) "fdata/pointer-map")
                (files/persist-pointers! conn id))))

          (dissoc file :data))))))

(def ^:private sql:retrieve-files-chunk
  "SELECT id, name, features, created_at, revn, data FROM file
    WHERE created_at < ? AND deleted_at is NULL
    ORDER BY created_at desc LIMIT ?")

(defn analyze-files
  "Apply a function to all files in the database, reading them in
  batches. Do not change data.

  The `on-file` parameter should be a function that receives the file
  and the previous state and returns the new state."
  [system & {:keys [chunk-size max-items start-at on-file on-error on-end on-init]
             :or {chunk-size 10 max-items Long/MAX_VALUE}}]
  (letfn [(get-chunk [conn cursor]
            (let [rows (db/exec! conn [sql:retrieve-files-chunk cursor chunk-size])]
              [(some->> rows peek :created-at) (seq rows)]))

          (get-candidates [conn]
            (->> (d/iteration (partial get-chunk conn)
                              :vf second
                              :kf first
                              :initk (or start-at (dt/now)))
                 (take max-items)
                 (map #(-> %
                           (update :data blob/decode)
                           (update :features db/decode-pgarray #{})))))

          (on-error* [cause file]
            (println "unexpected exception happened on processing file: " (:id file))
            (strace/print-stack-trace cause))]

    (when (fn? on-init) (on-init))

    (db/with-atomic [conn (:app.db/pool system)]
      (doseq [file (get-candidates conn)]
        (binding [*conn* conn
                  pmap/*tracked* (atom {})
                  pmap/*load-fn* (partial files/load-pointer conn (:id file))
                  cfeat/*wrap-with-pointer-map-fn*
                  (if (contains? (:features file) "fdata/pointer-map") pmap/wrap identity)
                  cfeat/*wrap-with-objects-map-fn*
                  (if (contains? (:features file) "fdata/objects-map") omap/wrap identity)]
          (try
            (on-file file)
            (catch Throwable cause
              ((or on-error on-error*) cause file))))))

    (when (fn? on-end) (on-end))))

(defn process-files!
  "Apply a function to all files in the database, reading them in
  batches."

  [{:keys [::db/pool] :as system} & {:keys [chunk-size
                                            max-items
                                            workers
                                            start-at
                                            on-file
                                            on-error
                                            on-end
                                            on-init]
                                     :or {chunk-size 10
                                          max-items Long/MAX_VALUE
                                          workers 1}}]

  (letfn [(get-chunk [conn cursor]
            (let [rows (db/exec! conn [sql:retrieve-files-chunk cursor chunk-size])]
              [(some->> rows peek :created-at)
               (map #(update % :features db/decode-pgarray #{}) rows)]))

          (get-candidates [conn]
            (->> (d/iteration (partial get-chunk conn)
                              :vf second
                              :kf first
                              :initk (or start-at (dt/now)))
                 (take max-items)))

          (on-error* [cause file]
            (println! "unexpected exception happened on processing file: " (:id file))
            (strace/print-stack-trace cause))

          (process-file [conn file]
            (try
              (binding [*conn* conn
                        pmap/*tracked* (atom {})
                        pmap/*load-fn* (partial files/load-pointer conn (:id file))
                        cfeat/*wrap-with-pointer-map-fn*
                        (if (contains? (:features file) "fdata/pointer-map") pmap/wrap identity)
                        cfeat/*wrap-with-objects-map-fn*
                        (if (contains? (:features file) "fdata/objectd-map") omap/wrap identity)]
                (on-file file))
              (catch Throwable cause
                ((or on-error on-error*) cause file))))

          (run-worker [in index]
            (db/with-atomic [conn pool]
              (loop [i 0]
                (when-let [file (sp/take! in)]
                  (println! "=> worker: index:" index "| loop:" i "| file:" (:id file) "|" (px/get-name))
                  (process-file conn file)
                  (recur (inc i))))))

          (run-producer [input]
            (db/with-atomic [conn pool]
              (doseq [file (get-candidates conn)]
                (println! "=> producer:" (:id file) "|" (px/get-name))
                (sp/put! input file))
              (sp/close! input)))

          (start-worker [input index]
            (px/thread
              {:name (str "penpot/srepl/worker/" index)}
              (run-worker input index)))
          ]

    (when (fn? on-init) (on-init))

    (let [input    (sp/chan :buf chunk-size)
          producer (px/thread
                     {:name "penpot/srepl/producer"}
                     (run-producer input))
          threads  (->> (range workers)
                        (map (partial start-worker input))
                        (cons producer)
                        (doall))]

      (run! p/await! threads)
      (when (fn? on-end) (on-end)))))

(defn update-pages
  "Apply a function to all pages of one file. The function receives a page and returns an updated page."
  [data f]
  (update data :pages-index update-vals f))

(defn update-shapes
  "Apply a function to all shapes of one page The function receives a shape and returns an updated shape"
  [page f]
  (update page :objects update-vals f))

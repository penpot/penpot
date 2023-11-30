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
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.migrations :as pmg]
   [app.common.logging :as l]
   [app.common.pprint :refer [pprint]]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.main :refer [system]]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.files-update :as files-update]
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
(def ^:dynamic *system* nil)

(defn println!
  [& params]
  (locking println
    (apply println params)))

(defn parse-uuid
  [v]
  (if (uuid? v)
    v
    (d/parse-uuid v)))

(defn reset-file-data!
  "Hardcode replace of the data of one file."
  [system id data]
  (db/tx-run! system (fn [system]
                       (db/update! system :file
                                   {:data data}
                                   {:id id}))))

(defn get-file
  "Get the migrated data of one file."
  [system id]
  (db/run! system
           (fn [{:keys [::db/conn]}]
             (binding [pmap/*load-fn* (partial files/load-pointer conn id)]
               (-> (files/get-file conn id)
                   (files/process-pointers deref))))))

(defn update-file!
  "Apply a function to the data of one file. Optionally save the changes or not.
  The function receives the decoded and migrated file data."
  [system & {:keys [update-fn id rollback? migrate? inc-revn?]
             :or {rollback? true migrate? true inc-revn? true}}]
  (letfn [(process-file [conn {:keys [features] :as file}]
            (binding [pmap/*tracked* (atom {})
                      pmap/*load-fn* (partial files/load-pointer conn id)
                      cfeat/*wrap-with-pointer-map-fn*
                      (if (contains? features "fdata/pointer-map") pmap/wrap identity)
                      cfeat/*wrap-with-objects-map-fn*
                      (if (contains? features "fdata/objectd-map") omap/wrap identity)]

              (let [file     (cond-> (update-fn file)
                               inc-revn? (update :revn inc))
                    features (db/create-array conn "text" (:features file))
                    data     (blob/encode (:data file))]

                (db/update! conn :file
                            {:data data
                             :revn (:revn file)
                             :features features}
                            {:id id}))

              (when (contains? (:features file) "fdata/pointer-map")
                (files/persist-pointers! conn id))

              (dissoc file :data)))]

    (db/tx-run! system
                (fn [{:keys [::db/conn] :as system}]
                  (binding [*conn* conn *system* system]
                    (try
                      (->> (files/get-file conn id :migrate? migrate?)
                           (process-file conn))
                      (finally
                        (when rollback?
                          (db/rollback! conn)))))))))

(defn analyze-files
  "Apply a function to all files in the database, reading them in
  batches. Do not change data.

  The `on-file` parameter should be a function that receives the file
  and the previous state and returns the new state.

  Emits rollback at the end of operation."
  [system & {:keys [chunk-size max-items start-at on-file on-error on-end on-init with-libraries?]
             :or {chunk-size 10 max-items Long/MAX_VALUE}}]
  (letfn [(get-chunk [conn cursor]
            (let [sql  (str "SELECT id, created_at FROM file "
                            " WHERE created_at < ? AND deleted_at is NULL "
                            " ORDER BY created_at desc LIMIT ?")
                  rows (db/exec! conn [sql cursor chunk-size])]
              [(some->> rows peek :created-at) (map :id rows)]))

          (get-candidates [conn]
            (->> (d/iteration (partial get-chunk conn)
                              :vf second
                              :kf first
                              :initk (or start-at (dt/now)))
                 (take max-items)))

          (on-error* [cause file]
            (println "unexpected exception happened on processing file: " (:id file))
            (strace/print-stack-trace cause))

          (process-file [conn file-id]
            (let [file (binding [pmap/*load-fn* (partial files/load-pointer conn file-id)]
                         (-> (files/get-file conn file-id)
                             (files/process-pointers deref)))

                  libs (when with-libraries?
                         (->> (files/get-file-libraries conn file-id)
                              (into [file] (map (fn [{:keys [id]}]
                                                  (binding [pmap/*load-fn* (partial files/load-pointer conn id)]
                                                    (-> (files/get-file conn id)
                                                        (files/process-pointers deref))))))
                              (d/index-by :id)))]
              (try
                (if with-libraries?
                  (on-file file libs)
                  (on-file file))
                (catch Throwable cause
                  ((or on-error on-error*) cause file)))))]

    (db/tx-run! system
                (fn [{:keys [::db/conn] :as system}]
                  (try
                    (binding [*conn* conn *system* system]
                      (when (fn? on-init) (on-init))
                      (run! (partial process-file conn) (get-candidates conn)))
                    (finally
                      (when (fn? on-end)
                        (ex/ignoring (on-end)))
                      (db/rollback! conn)))))))

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
                                            on-init
                                            rollback?]
                                     :or {chunk-size 10
                                          max-items Long/MAX_VALUE
                                          workers 1
                                          rollback? true}}]
  (letfn [(get-chunk [conn cursor]
            (let [sql  (str "SELECT id, created_at FROM file "
                            " WHERE created_at < ? AND deleted_at is NULL "
                            " ORDER BY created_at desc LIMIT ?")
                  rows (db/exec! conn [sql cursor chunk-size])]
              [(some->> rows peek :created-at) (map :id rows)]))

          (get-candidates [conn]
            (->> (d/iteration (partial get-chunk conn)
                              :vf second
                              :kf first
                              :initk (or start-at (dt/now)))
                 (take max-items)))

          (on-error* [cause file]
            (println! "unexpected exception happened on processing file: " (:id file))
            (strace/print-stack-trace cause))

          (process-file [conn file-id]
            (try
              (let [{:keys [features] :as file} (files/get-file conn file-id)]
                (binding [pmap/*tracked* (atom {})
                          pmap/*load-fn* (partial files/load-pointer conn file-id)
                          cfeat/*wrap-with-pointer-map-fn*
                          (if (contains? features "fdata/pointer-map") pmap/wrap identity)
                          cfeat/*wrap-with-objects-map-fn*
                          (if (contains? features "fdata/objectd-map") omap/wrap identity)]

                  (on-file file)

                  (when (contains? features "fdata/pointer-map")
                    (files/persist-pointers! conn file-id))))

              (catch Throwable cause
                ((or on-error on-error*) cause file-id))))

          (run-worker [in index]
            (db/tx-run! system
                        (fn [{:keys [::db/conn] :as system}]
                          (binding [*conn* conn *system* system]
                            (loop [i 0]
                              (when-let [file-id (sp/take! in)]
                                (println! "=> worker: index:" index "| loop:" i "| file:" (str file-id) "|" (px/get-name))
                                (process-file conn file-id)
                                (recur (inc i)))))

                          (when rollback?
                            (db/rollback! conn)))))

          (run-producer [input]
            (db/with-atomic [conn pool]
              (doseq [file-id (get-candidates conn)]
                (println! "=> producer:" file-id "|" (px/get-name))
                (sp/put! input file-id))
              (sp/close! input)))]

    (when (fn? on-init) (on-init))

    (let [input    (sp/chan :buf chunk-size)
          producer (px/thread
                     {:name "penpot/srepl/producer"}
                     (run-producer input))
          threads  (->> (range workers)
                        (map (fn [index]
                               (px/thread
                                 {:name (str "penpot/srepl/worker/" index)}
                                 (run-worker input index))))
                        (cons producer)
                        (doall))]

      (run! p/await! threads)
      (when (fn? on-end) (on-end)))))

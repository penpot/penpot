;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.helpers
  "A  main namespace for server repl."
  (:refer-clojure :exclude [parse-uuid])
  (:require
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.common.files.migrations :as fmg]
   [app.common.files.validate :as cfv]
   [app.db :as db]
   [app.features.components-v2 :as feat.comp-v2]
   [app.main :as main]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.files-snapshot :as fsnap]
   [app.util.time :as dt]))

(def ^:dynamic *system* nil)

(defn println!
  [& params]
  (locking println
    (apply println params)))

(defn get-current-system
  []
  *system*)

(defn parse-uuid
  [v]
  (if (string? v)
    (d/parse-uuid v)
    v))

(defn get-file
  "Get the migrated data of one file."
  ([id]
   (get-file (or *system* main/system) id))
  ([system id]
   (db/run! system bfc/get-file id)))

(defn get-raw-file
  "Get the migrated data of one file."
  ([id] (get-raw-file (or *system* main/system) id))
  ([system id]
   (db/run! system
            (fn [system]
              (files/get-file system id :migrate? false)))))

(defn update-team!
  [system {:keys [id] :as team}]
  (let [conn   (db/get-connection system)
        params (-> team
                   (update :features db/encode-pgarray conn "text")
                   (dissoc :id))]
    (db/update! conn :team
                params
                {:id id})
    team))

(defn reset-file-data!
  "Hardcode replace of the data of one file."
  [system id data]
  (db/tx-run! system
              (fn [system]
                (db/update! system :file
                            {:data data}
                            {:id id}))))


(def ^:private sql:snapshots-with-file
  "WITH files AS (
     SELECT f.id AS file_id,
            (SELECT fc.id
               FROM file_change AS fc
              WHERE fc.label = ?
                AND fc.file_id = f.id
              ORDER BY fc.created_at DESC
              LIMIT 1) AS id
       FROM file AS f
   ) SELECT * FROM files
      WHERE file_id = ANY(?)
        AND id IS NOT NULL")

(defn search-file-snapshots
  "Get a seq parirs of file-id and snapshot-id for a set of files
   and specified label"
  [conn file-ids label]
  (db/exec! conn [sql:snapshots-with-file label
                  (db/create-array conn "uuid" file-ids)]))

(defn take-team-snapshot!
  [system team-id label]
  (let [conn (db/get-connection system)]
    (->> (feat.comp-v2/get-and-lock-team-files conn team-id)
         (reduce (fn [result file-id]
                   (let [file (fsnap/get-file-snapshots system file-id)]
                     (fsnap/create-file-snapshot! system file
                                                  {:label label
                                                   :created-by :admin})
                     (inc result)))
                 0))))

(defn restore-team-snapshot!
  [system team-id label]
  (let [conn (db/get-connection system)
        ids  (->> (feat.comp-v2/get-and-lock-team-files conn team-id)
                  (into #{}))

        snap (search-file-snapshots conn ids label)

        ids' (into #{} (map :file-id) snap)
        team (-> (feat.comp-v2/get-team conn team-id)
                 (update :features disj "components/v2"))]

    (when (not= ids ids')
      (throw (RuntimeException. "no uniform snapshot available")))

    (feat.comp-v2/update-team! conn team)
    (reduce (fn [result {:keys [file-id id]}]
              (fsnap/restore-file-snapshot! system file-id id)
              (inc result))
            0
            snap)))

(defn process-file!
  [system file-id update-fn & {:keys [label validate? with-libraries?] :or {validate? true} :as opts}]
  (let [conn  (db/get-connection system)
        file  (bfc/get-file system file-id ::db/for-update true)
        libs  (when with-libraries?
                (->> (files/get-file-libraries conn file-id)
                     (into [file] (map (fn [{:keys [id]}]
                                         (bfc/get-file system id))))
                     (d/index-by :id)))

        file' (when file
                (if with-libraries?
                  (update-fn file libs opts)
                  (update-fn file opts)))]

    (when (and (some? file')
               (or (fmg/migrated? file)
                   (not (identical? file file'))))

      (when validate?
        (cfv/validate-file-schema! file'))

      (when (string? label)
        (fsnap/create-file-snapshot! system file
                                     {:label label
                                      :deleted-at (dt/in-future {:days 30})
                                      :created-by :admin}))

      (let [file' (update file' :revn inc)]
        (bfc/update-file! system file')
        true))))

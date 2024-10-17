;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.helpers
  "A  main namespace for server repl."
  (:refer-clojure :exclude [parse-uuid])
  (:require
   [app.common.data :as d]
   [app.common.files.migrations :as fmg]
   [app.common.files.validate :as cfv]
   [app.db :as db]
   [app.features.components-v2 :as feat.comp-v2]
   [app.features.fdata :as feat.fdata]
   [app.main :as main]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.files-snapshot :as fsnap]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]))

(def ^:dynamic *system* nil)

(defn println!
  [& params]
  (locking println
    (apply println params)))

(defn parse-uuid
  [v]
  (if (string? v)
    (d/parse-uuid v)
    v))

(defn get-file
  "Get the migrated data of one file."
  ([id] (get-file (or *system* main/system) id nil))
  ([system id & {:keys [raw?] :as opts}]
   (db/run! system
            (fn [system]
              (let [file (files/get-file system id :migrate? false)]
                (if raw?
                  file
                  (binding [pmap/*load-fn* (partial feat.fdata/load-pointer system id)]
                    (-> file
                        (update :data feat.fdata/process-pointers deref)
                        (update :data feat.fdata/process-objects (partial into {}))
                        (fmg/migrate-file)))))))))

(defn update-file!
  [system {:keys [id] :as file}]
  (let [conn (db/get-connection system)
        file (if (contains? (:features file) "fdata/objects-map")
               (feat.fdata/enable-objects-map file)
               file)

        file (if (contains? (:features file) "fdata/pointer-map")
               (binding [pmap/*tracked* (pmap/create-tracked)]
                 (let [file (feat.fdata/enable-pointer-map file)]
                   (feat.fdata/persist-pointers! system id)
                   file))
               file)

        file (-> file
                 (update :features db/encode-pgarray conn "text")
                 (update :data blob/encode))]

    (db/update! conn :file
                {:revn (:revn file)
                 :data (:data file)
                 :version (:version file)
                 :features (:features file)
                 :deleted-at (:deleted-at file)
                 :created-at (:created-at file)
                 :modified-at (:modified-at file)
                 :data-backend nil
                 :data-ref-id nil
                 :has-media-trimmed false}
                {:id (:id file)})))

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

(defn get-raw-file
  "Get the migrated data of one file."
  ([id] (get-raw-file (or *system* main/system) id))
  ([system id]
   (db/run! system
            (fn [system]
              (files/get-file system id :migrate? false)))))

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

(defn get-file-snapshots
  "Get a seq parirs of file-id and snapshot-id for a set of files
   and specified label"
  [conn label ids]
  (db/exec! conn [sql:snapshots-with-file label
                  (db/create-array conn "uuid" ids)]))

(defn take-team-snapshot!
  [system team-id label]
  (let [conn (db/get-connection system)]
    (->> (feat.comp-v2/get-and-lock-team-files conn team-id)
         (map (fn [file-id]
                {:file-id file-id
                 :label label}))
         (reduce (fn [result params]
                   (fsnap/take-file-snapshot! conn params)
                   (inc result))
                 0))))

(defn restore-team-snapshot!
  [system team-id label]
  (let [conn (db/get-connection system)
        ids  (->> (feat.comp-v2/get-and-lock-team-files conn team-id)
                  (into #{}))

        snap (get-file-snapshots conn label ids)

        ids' (into #{} (map :file-id) snap)
        team (-> (feat.comp-v2/get-team conn team-id)
                 (update :features disj "components/v2"))]

    (when (not= ids ids')
      (throw (RuntimeException. "no uniform snapshot available")))

    (feat.comp-v2/update-team! conn team)
    (reduce (fn [result params]
              (fsnap/restore-file-snapshot! conn params)
              (inc result))
            0
            snap)))

(defn process-file!
  [system file-id update-fn & {:keys [label validate? with-libraries?] :or {validate? true} :as opts}]

  (when (string? label)
    (fsnap/take-file-snapshot! system {:file-id file-id :label label}))

  (let [conn  (db/get-connection system)
        file  (get-file system file-id opts)
        libs  (when with-libraries?
                (->> (files/get-file-libraries conn file-id)
                     (into [file] (map (fn [{:keys [id]}]
                                         (get-file system id))))
                     (d/index-by :id)))

        file' (if with-libraries?
                (update-fn file libs opts)
                (update-fn file opts))]

    (when (and (some? file')
               (not (identical? file file')))
      (when validate? (cfv/validate-file-schema! file'))
      (let [file' (update file' :revn inc)]
        (update-file! system file')
        true))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.features.file-change-refs
  (:require
   [app.common.exceptions :as ex]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.features.file-changes :as fchanges]))

(def ^:private sql:missing-ref
  ;; fragment_ids is NOT NULL: a matching row is sufficient even when its
  ;; array is empty, and this probe need not load the array.
  "NOT EXISTS (
     SELECT 1 FROM file_change_refs r
      WHERE r.file_id = c.file_id AND r.file_change_id = c.id
   )")

(def ^:private sql:get-file-change-for-indexing
  (str "SELECT c.id, c.file_id, c.kind,
               CASE WHEN fd.id IS NULL AND c.kind = ? THEN c.data END AS legacy_data,
               fd.data, COALESCE(fd.backend, 'legacy-db') AS backend, fd.storage_object_id
          FROM file_change c
          LEFT JOIN file_data fd ON (fd.file_id = c.file_id AND
                                     fd.id = c.id AND
                                     fd.type = 'snapshot' AND
                                     c.kind = ?)
         WHERE c.file_id = ? AND c.id = ? AND " sql:missing-ref))

(def ^:private sql:insert-refs
  ;; Take the row lock only at publication, not during the payload read.
  ;; When a concurrent purge won, the locking SELECT yields no row; when
  ;; this insert wins, the FK removes its result with the source.
  "INSERT INTO file_change_refs (file_id, file_change_id, fragment_ids)
   SELECT c.file_id, c.id, ?::uuid[]
     FROM file_change c
    WHERE c.file_id = ? AND c.id = ?
      FOR KEY SHARE OF c
   ON CONFLICT (file_id, file_change_id) DO NOTHING
   RETURNING file_change_id")

(defn- get-file-change-for-indexing
  [cfg file-id change-id]
  (db/exec-one! cfg [sql:get-file-change-for-indexing
                     fchanges/snapshot-kind fchanges/snapshot-kind file-id change-id]))

(defn- get-snapshot-fragment-ids
  [cfg {:keys [file-id id] :as source}]
  (let [file-data (fdata/resolve-file-data cfg source)]
    (when (fdata/has-payload? file-data)
      (let [{:keys [data]} (fdata/decode-file-data cfg file-data)]
        (if (map? data)
          (fdata/get-used-pointer-ids data)
          (ex/raise :type :internal
                    :code :invalid-snapshot-payload
                    :hint "snapshot payload is not a map"
                    :file-id file-id
                    :id id))))))

(defn index-change
  "Index the persisted fragment refs of a file change, if not already
  indexed. Snapshots are immutable under this key; offload copies their
  bytes without changing the refs. Revisions do not require a payload read.

  Returns true if a coverage row was inserted, even with no fragment refs.
  Returns false if the payload is absent or the change is already covered or
  purged. Invalid kinds, malformed payloads and read errors propagate."
  [{:keys [::db/conn] :as cfg} file-id change-id]
  (boolean
   (when-let [{:keys [kind] :as source} (get-file-change-for-indexing cfg file-id change-id)]
     (let [ids (cond
                 (= kind fchanges/snapshot-kind)
                 (get-snapshot-fragment-ids cfg source)

                 (= kind fchanges/revision-kind)
                 []

                 :else
                 (ex/raise :type :internal
                           :code :invalid-file-change-kind
                           :hint "file change kind is missing or unsupported"
                           :file-id file-id
                           :change-id change-id
                           :kind kind))]
       (when (and (some? ids)
                  (db/exec-one! conn [sql:insert-refs
                                      (db/create-array conn "uuid" ids)
                                      file-id change-id]))
         true)))))

(defn get-missing-change-ids
  "Return up to `limit` uncovered file change IDs in database UUID order,
  including marked changes. Pass `:after-id` to resume after a previous
  page; omit it when checking coverage of the whole file."
  [cfg file-id limit & {:keys [after-id]}]
  (let [sql (str "SELECT c.id FROM file_change c WHERE c.file_id = ? AND "
                 (when after-id "c.id > ? AND ")
                 sql:missing-ref " ORDER BY c.id LIMIT ?")]
    (->> (if after-id
           (db/exec! cfg [sql file-id after-id limit])
           (db/exec! cfg [sql file-id limit]))
         (mapv :id))))

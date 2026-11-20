;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.features.file-changes
  "The kinds of the rows of the `file_change` table.

  A snapshot is the state of a file at a point: it has a label and its
  data lives in `file_data` (old `legacy-db` payloads live in
  `file_change.data`). A revision is the delta of a save: it has
  no label and it holds the changes."
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.util.blob :as blob]))

(def snapshot-kind
  "The kind of a snapshot row."
  1)

(def revision-kind
  "The kind of a revision row."
  2)

(defn insert-revision
  "Inserts the revision row of a save on `file`: `changes` is the whole of
  what the save applied, not encoded. The row expires one hour after
  `timestamp`, unless the `file-change-retention` flag is set, which keeps
  it: `objects-gc` collects only rows with a `deleted_at`. The same flag
  makes file-gc skip its media clean-up, which does not read these rows,
  so the flag must be set only where both halves are deployed. Every writer
  of a revision row goes through this function, so that the `kind`, the
  data `version`, the `updated_at` the partition runbook copies by, and the
  expiry are always set, and retention is decided here alone.

  `id` is optional. A save passes the id its audit event carries, so that
  the event points at the row really inserted. Returns the row's `:id`
  and `:deleted-at`, so that a caller that writes a companion row for the
  same revision can give it the same identity and the same expiry."
  [{:keys [::db/conn] :as _cfg} {:keys [version] :as file} timestamp
   {:keys [id session-id profile-id revn changes]}]
  (let [id         (or id (uuid/next))
        deleted-at (when-not (contains? cf/flags :file-change-retention)
                     (ct/plus timestamp (ct/duration {:hours 1})))]
    (db/insert! conn :file-change
                {:id id
                 :kind revision-kind
                 :session-id session-id
                 :profile-id profile-id
                 :created-at timestamp
                 :updated-at timestamp
                 :deleted-at deleted-at
                 :file-id (:id file)
                 :revn revn
                 :version version
                 :changes (blob/encode changes)}
                {::db/return-keys false})
    {:id id :deleted-at deleted-at}))

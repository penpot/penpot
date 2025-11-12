;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.features.file-migrations
  "Backend specific code for file migrations. Implemented as permanent feature of files."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.files.migrations :as fmg :refer [xf:map-name]]
   [app.db :as db]
   [app.db.sql :as-alias sql]))

(def ^:private sql:get-file-migrations
  "SELECT name FROM file_migration WHERE file_id = ? ORDER BY created_at ASC")

(defn resolve-applied-migrations
  [cfg {:keys [id] :as file}]
  (let [conn (db/get-connection cfg)]
    (assoc file :migrations
           (->> (db/plan conn [sql:get-file-migrations id])
                (transduce xf:map-name conj (d/ordered-set))
                (not-empty)))))

(defn upsert-migrations!
  "Persist or update file migrations. Return the updated/inserted number
  of rows"
  [cfg {:keys [id] :as file}]
  (let [conn       (db/get-connection cfg)
        migrations (or (-> file meta ::fmg/migrated)
                       (-> file :migrations))
        columns    [:file-id :name]
        rows       (->> migrations
                        (mapv (fn [name] [id name]))
                        (not-empty))]

    (when-not rows
      (ex/raise :type :internal
                :code :missing-migrations
                :hint "no migrations available on file"))

    (-> (db/insert-many! conn :file-migration columns rows
                         {::db/return-keys false
                          ::sql/on-conflict-do-nothing true})
        (db/get-update-count))))

(defn reset-migrations!
  "Replace file migrations"
  [cfg {:keys [id] :as file}]
  (db/delete! cfg :file-migration {:file-id id})
  (upsert-migrations! cfg file))

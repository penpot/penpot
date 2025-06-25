;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.fixes.lost-colors
  "A collection of adhoc fixes scripts."
  (:require
   [app.binfile.common :as bfc]
   [app.common.logging :as l]
   [app.common.types.color :as types.color]
   [app.db :as db]
   [app.srepl.helpers :as h]))

(def sql:get-affected-files
  "SELECT fm.file_id AS id FROM file_migration AS fm WHERE fm.name = '0008-fix-library-colors-v2'")

(def sql:get-matching-snapshot
  "SELECT * FROM file_change
    WHERE file_id = ?
      AND created_at <= ?
      AND label IS NOT NULL
      AND data IS NOT NULL
    ORDER BY created_at DESC
    LIMIT 1")

(defn get-affected-migration
  [conn file-id]
  (db/get* conn :file-migration
           {:name "0008-fix-library-colors-v2"
            :file-id file-id}))

(defn get-last-valid-snapshot
  [conn migration]
  (when-let [snapshot (db/exec-one! conn [sql:get-matching-snapshot
                                          (:file-id migration)
                                          (:created-at migration)])]
    (let [snapshot (assoc snapshot :id (:file-id snapshot))]
      (bfc/decode-file h/*system* snapshot))))

(defn restore-color
  [{:keys [data] :as snapshot} color]
  (when-let [scolor (get-in data [:colors (:id color)])]
    (-> (select-keys scolor types.color/library-color-attrs)
        (types.color/check-library-color))))

(defn restore-missing-colors
  [{:keys [id] :as file} & _opts]
  (when-let [colors (-> file :data :colors not-empty)]
    (when-let [migration (get-affected-migration h/*system* id)]
      (when-let [snapshot (get-last-valid-snapshot h/*system* migration)]
        (let [colors (reduce-kv (fn [colors color-id color]
                                  (if-let [result (restore-color snapshot color)]
                                    (do
                                      (l/inf :hint "restored color" :file-id (str id) :color-id (str color-id))
                                      (assoc colors color-id result))
                                    (do
                                      (l/wrn :hint "ignoring color" :file-id (str id) :color (pr-str color))
                                      colors)))
                                colors
                                colors)
              file   (-> file
                         (update :data assoc :colors colors)
                         (update :migrations disj "0008-fix-library-colors-v2"))]

          (db/delete! h/*system* :file-migration
                      {:name "0008-fix-library-colors-v2"
                       :file-id (:id file)})

          file)))))

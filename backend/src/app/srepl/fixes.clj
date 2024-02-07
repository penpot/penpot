;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.fixes
  "A misc of fix functions"
  (:refer-clojure :exclude [parse-uuid])
  (:require
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.common.files.changes :as cpc]
   [app.common.files.repair :as cfr]
   [app.common.files.validate :as cfv]
   [app.common.logging :as l]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.srepl.helpers :as h]))

(defn repair-file-media
  "A helper intended to be used with `srepl.main/process-files!` that
  fixes all not propertly referenced file-media-object for a file"
  [{:keys [id data] :as file} & _]
  (let [conn  (db/get-connection h/*system*)
        used  (bfc/collect-used-media data)
        ids   (db/create-array conn "uuid" used)
        sql   (str "SELECT * FROM file_media_object WHERE id = ANY(?)")
        rows  (db/exec! conn [sql ids])
        index (reduce (fn [index media]
                        (if (not= (:file-id media) id)
                          (let [media-id (uuid/next)]
                            (l/wrn :hint "found not referenced media"
                                   :file-id (str id)
                                   :media-id (str (:id media)))

                            (db/insert! conn :file-media-object
                                        (-> media
                                            (assoc :file-id id)
                                            (assoc :id media-id)))
                            (assoc index (:id media) media-id))
                          index))
                      {}
                      rows)]

    (when (seq index)
      (binding [bfc/*state* (atom {:index index})]
        (update file :data (fn [fdata]
                             (-> fdata
                                 (update :pages-index #'bfc/relink-shapes)
                                 (update :components #'bfc/relink-shapes)
                                 (update :media #'bfc/relink-media)
                                 (d/without-nils))))))))


(defn repair-file
  "Internal helper for validate and repair the file. The operation is
  applied multiple times untile file is fixed or max iteration counter
  is reached (default 10)"
  [file libs & {:keys [max-iterations] :or {max-iterations 10}}]

  (let [validate-and-repair
        (fn [file libs iteration]
          (when-let [errors (not-empty (cfv/validate-file file libs))]
            (l/trc :hint "repairing file"
                   :file-id (str (:id file))
                   :iteration iteration
                   :errors (count errors))
            (let [changes (cfr/repair-file file libs errors)]
              (-> file
                  (update :revn inc)
                  (update :data cpc/process-changes changes)))))

        process-file
        (fn [file libs]
          (loop [file      file
                 iteration 0]
            (if (< iteration max-iterations)
              (if-let [file (validate-and-repair file libs iteration)]
                (recur file (inc iteration))
                file)
              (do
                (l/wrn :hint "max retry num reached on repairing file"
                       :file-id (str (:id file))
                       :iteration iteration)
                file))))

        file'
        (process-file file libs)]

    (when (not= (:revn file) (:revn file'))
      (l/trc :hint "file repaired" :file-id (str (:id file))))

    file'))
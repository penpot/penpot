;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.procs.file-repair
  (:require
   [app.common.files.changes :as cfc]
   [app.common.files.repair :as cfr]
   [app.common.files.validate :as cfv]
   [app.common.logging :as l]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GENERAL PURPOSE REPAIR
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn repair-file
  "Internal helper for validate and repair the file. The operation is
  applied multiple times untile file is fixed or max iteration counter
  is reached (default 10).

  This function should not be used directly, it is used throught the
  app.srepl.main/repair-file! helper. In practical terms this function
  is private and implementation detail."
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
                  (update :data cfc/process-changes changes)))))

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

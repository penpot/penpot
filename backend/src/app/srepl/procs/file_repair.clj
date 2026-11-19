;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.srepl.procs.file-repair
  (:require
   [app.common.files.repair :as cfr]
   [app.common.files.validate :as cfv]
   [app.common.logging :as l]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GENERAL PURPOSE REPAIR
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn repair-file
  "Internal helper for validate and repair the file. The operation is
  applied multiple times until file is fixed or max iteration counter
  is reached (default 10).

  This function should not be used directly, it is used through the
  app.srepl.main/repair-file! helper. In practical terms this function
  is private and implementation detail."
  [file libs & {:keys [max-iterations] :or {max-iterations 10}}]

  (let [orig-revn (:revn file)
        result    (cfr/repair-file-until-valid
                   file libs
                   (fn [file _]
                     (cfv/validate-file file libs))
                   :max-iterations max-iterations)
        file'     (update (:file result) :revn (fnil + 0) (:iterations result))]

    (when (>= (:iterations result) max-iterations)
      (l/wrn :hint "max retry num reached on repairing file"
             :file-id (str (:id file))
             :iteration (:iterations result)))

    (when (not= orig-revn (:revn file'))
      (l/trc :hint "file repaired" :file-id (str (:id file))))

    file'))

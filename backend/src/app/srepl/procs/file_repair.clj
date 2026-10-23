;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.srepl.procs.file-repair
  (:require
   [app.common.files.repair :as cfr]
   [app.common.types.file :as ctf]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GENERAL PURPOSE REPAIR
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn repair-file
  "Internal helper for validate and repair the file. Sanitizes first
  (the shared loop does not) so unconverted types do not choke
  validation, then delegates to the shared `cfr/repair-loop` (the
  same loop the file-gc task uses), so convergence rules stay in one
  place.

  This function should not be used directly, it is used throught the
  app.srepl.main/repair-file! helper. In practical terms this function
  is private and implementation detail."
  [file libs & {:keys [max-iterations]}]
  (cfr/repair-loop (update file :data ctf/sanitize-file-data) libs :max-iterations max-iterations))

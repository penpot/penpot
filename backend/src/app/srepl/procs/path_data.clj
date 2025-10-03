;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.procs.path-data
  (:require
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.logging :as l]
   [app.srepl.helpers :as h]))

(def ^:private sql:get-files-with-path-data
  "SELECT id FROM file WHERE features @> '{fdata/path-data}'")

(defn disable
  "A script responsible for remove the path data type from file data and
  allow file to be open in older penpot versions.

  Should be used only in cases when you want to downgrade to an older
  penpot version for some reason."
  {:query sql:get-files-with-path-data}
  [cfg {:keys [id]} & {:as options}]

  (l/inf :hint "disabling path-data" :file-id (str id))

  (let [update-object
        (fn [object]
          (if (or (cfh/path-shape? object)
                  (cfh/bool-shape? object))
            (update object :content vec)
            object))

        update-container
        (fn [container]
          (d/update-when container :objects d/update-vals update-object))

        update-file
        (fn [file & _opts]
          (-> file
              (update :data (fn [data]
                              (-> data
                                  (update :pages-index d/update-vals update-container)
                                  (d/update-when :components d/update-vals update-container))))
              (update :features disj "fdata/path-data")
              (update :migrations disj
                      "0003-convert-path-content-v2"
                      "0003-convert-path-content")))

        options
        (-> options
            (assoc ::bfc/reset-migrations? true)
            (assoc ::h/validate? false))]

    (h/process-file! cfg id update-file options)))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.procs.media-refs
  (:require
   [app.binfile.common :as bfc]
   [app.common.files.helpers :as cfh]
   [app.common.logging :as l]
   [app.srepl.helpers :as h]))

(defn- collect-media-refs
  "Given a file data map, returns all media references used on pages of
  the file data; components and other parts of the file data are not analized"
  [data]
  (let [xform-collect
        (comp
         (map val)
         (mapcat (fn [object]
                   (->> (cfh/collect-shape-media-refs object)
                        (map (fn [id]
                               {:shape-id (:id object)
                                :id id}))))))
        process-page
        (fn [result page-id container]
          (let [xform (comp xform-collect (map #(assoc % :page-id page-id)))]
            (into result xform (:objects container))))]

    (reduce-kv process-page [] (:pages-index data))))

(defn update-all-media-references
  "Check if all file media object references are in plance and create
  new ones if some of them are missing; this prevents strange bugs on
  having the same media object associated with two different files;"
  [cfg file]
  (let [media-refs (collect-media-refs (:data file))]
    (bfc/update-media-references! cfg file media-refs)))

(def ^:private sql:get-files
  "SELECT f.id
     FROM file AS f
     LEFT JOIN file_migration AS fm ON (fm.file_id = f.id AND fm.name = 'internal/procs/media-refs')
    WHERE fm.name IS NULL
    ORDER BY f.project_id")

(defn fix-media-refs
  {:query sql:get-files}
  [cfg {:keys [id]} & {:as options}]
  (l/inf :hint "processing file" :id (str id))

  (h/process-file! cfg id
                   (fn [file _opts]
                     (update-all-media-references cfg file))
                   (assoc options
                          ::bfc/reset-migrations? true
                          ::h/validate? false))
  (h/mark-migrated! cfg id "internal/procs/media-refs"))

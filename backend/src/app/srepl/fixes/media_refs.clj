;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.fixes.media-refs
  (:require
   [app.binfile.common :as bfc]
   [app.common.files.helpers :as cfh]
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

(defn process-file
  [file _opts]
  (let [system (h/get-current-system)]
    (update-all-media-references system file)))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.fixes
  "A collection of adhoc fixes scripts."
  (:require
   [app.common.logging :as l]
   [app.common.uuid :as uuid]
   [app.srepl.helpers :as h]))

(defn repair-orphaned-shapes
  "There are some shapes whose parent has been deleted. This function
  detects them and puts them as children of the root node."
  ([data]
   (letfn [(is-orphan? [shape objects]
             (and (some? (:parent-id shape))
                  (nil? (get objects (:parent-id shape)))))

           (update-page [page]
             (let [objects (:objects page)
                   orphans (into #{} (filter #(is-orphan? % objects)) (vals objects))]
               (if (seq orphans)
                 (do
                   (l/info :hint "found a file with orphans" :file-id (:id data) :broken-shapes (count orphans))
                   (-> page
                       (h/update-shapes (fn [shape]
                                          (if (contains? orphans shape)
                                            (assoc shape :parent-id uuid/zero)
                                            shape)))
                       (update-in [:objects uuid/zero :shapes] into (map :id) orphans)))
                 page)))]

     (h/update-pages data update-page)))

  ;; special arity for to be called from h/analyze-files to search for
  ;; files with possible issues

  ([file state]
   (repair-orphaned-shapes (:data file))
   (update state :total (fnil inc 0))))

(defn rename-layout-attrs
  ([file]
   (let [found? (volatile! false)]
     (letfn [(update-shape
               [shape]
               (when (or (= (:layout-flex-dir shape) :reverse-row)
                         (= (:layout-flex-dir shape) :reverse-column)
                         (= (:layout-wrap-type shape) :no-wrap))
                 (vreset! found? true))
               (cond-> shape
                 (= (:layout-flex-dir shape) :reverse-row)
                 (assoc :layout-flex-dir :row-reverse)
                 (= (:layout-flex-dir shape) :reverse-column)
                 (assoc :layout-flex-dir :column-reverse)
                 (= (:layout-wrap-type shape) :no-wrap)
                 (assoc :layout-wrap-type :nowrap)))

             (update-page
               [page]
               (h/update-shapes page update-shape))]

       (let [new-file (update file :data h/update-pages update-page)]
         (when @found?
           (l/info :hint "Found attrs to rename in file"
                   :id (:id file)
                   :name (:name file)))
         new-file))))

   ([file state]
    (rename-layout-attrs file)
    (update state :total (fnil inc 0))))
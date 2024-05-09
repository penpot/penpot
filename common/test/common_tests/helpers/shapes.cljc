;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.helpers.shapes
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.transit :as t]
   [app.common.types.color :as ctc]
   [app.common.types.colors-list :as ctcl]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.typographies-list :as cttl]
   [app.common.types.typography :as ctt]
   [app.common.uuid :as uuid]
   [common-tests.helpers.files :as thf]
   [common-tests.helpers.ids-map :as thi]))

(defn sample-shape
  [label & {:keys [type] :as params}]
  (let [params (cond-> params
                 label
                 (assoc :id (thi/new-id! label))

                 (nil? type)
                 (assoc :type :rect))]

    (cts/setup-shape params)))

(defn add-sample-shape
  [file label & {:keys [parent-label] :as params}]
  (let [page      (thf/current-page file)
        shape     (sample-shape label (dissoc params :parent-label))
        parent-id (when parent-label
                    (thi/id parent-label))
        parent    (when parent-id
                    (ctst/get-shape page parent-id))
        frame-id  (if (cfh/frame-shape? parent)
                    (:id parent)
                    (:frame-id parent))]
    (ctf/update-file-data
     file
     (fn [file-data]
       (ctpl/update-page file-data
                         (:id page)
                         #(ctst/add-shape (:id shape)
                                          shape
                                          %
                                          frame-id
                                          parent-id
                                          nil
                                          true))))))

(defn get-shape
  [file label & {:keys [page-label]}]
  (let [page (if page-label
               (thf/get-page file page-label)
               (thf/current-page file))]
    (ctst/get-shape page (thi/id label))))

(defn get-shape-by-id
  [file id & {:keys [page-label]}]
  (let [page (if page-label
               (thf/get-page file page-label)
               (thf/current-page file))]
    (ctst/get-shape page id)))

(defn sample-color
  [label & {:keys [] :as params}]
  (ctc/make-color (assoc params :id (thi/new-id! label))))

(defn sample-fill-color
  [& {:keys [fill-color fill-opacity] :as params}]
  (let [params (cond-> params
                 (nil? fill-color)
                 (assoc :fill-color clr/black)

                 (nil? fill-opacity)
                 (assoc :fill-opacity 1))]
    params))

(defn sample-fills-color
  [& {:keys [] :as params}]
  [(sample-fill-color params)])

(defn add-sample-library-color
  [file label & {:keys [] :as params}]
  (let [color (sample-color label params)]
    (ctf/update-file-data file #(ctcl/add-color % color))))

(defn sample-typography
  [label & {:keys [] :as params}]
  (ctt/make-typography (assoc params :id (thi/new-id! label))))

(defn add-sample-typography
  [file label & {:keys [] :as params}]
  (let [typography (sample-typography label params)]
    (ctf/update-file-data file #(cttl/add-typography % typography))))

(defn simulate-copy-shape
  [selected objects libraries page file features version]
  (letfn [(sort-selected [data]
            (let [;; Narrow the objects map so it contains only relevant data for
                  ;; selected and its parents
                  objects  (cfh/selected-subtree objects selected)
                  selected (->> (ctst/sort-z-index objects selected)
                                (reverse)
                                (into (d/ordered-set)))]

              (assoc data :selected selected)))

          ;; Prepare the shape object.
          (prepare-object [objects parent-frame-id obj]
            (maybe-translate obj objects parent-frame-id))

          ;; Collects all the items together and split images into a
          ;; separated data structure for a more easy paste process.
          (collect-data [result {:keys [id ::images] :as item}]
            (cond-> result
              :always
              (update :objects assoc id (dissoc item ::images))

              (some? images)
              (update :images into images)))

          (maybe-translate [shape objects parent-frame-id]
            (if (= parent-frame-id uuid/zero)
              shape
              (let [frame (get objects parent-frame-id)]
                (gsh/translate-to-frame shape frame))))

          ;; When copying an instance that is nested inside another one, we need to
          ;; advance the shape refs to one or more levels of remote mains.
          (advance-copies [data]
            (let [heads     (mapcat #(ctn/get-child-heads (:objects data) %) selected)]
              (update data :objects
                      #(reduce (partial advance-copy file libraries page)
                               %
                               heads))))

          (advance-copy [file libraries page objects shape]
            (if (and (ctk/instance-head? shape) (not (ctk/main-instance? shape)))
              (let [level-delta (ctn/get-nesting-level-delta (:objects page) shape uuid/zero)]
                (if (pos? level-delta)
                  (reduce (partial advance-shape file libraries page level-delta)
                          objects
                          (cfh/get-children-with-self objects (:id shape)))
                  objects))
              objects))

          (advance-shape [file libraries page level-delta objects shape]
            (let [new-shape-ref (ctf/advance-shape-ref file page libraries shape level-delta {:include-deleted? true})]
              (cond-> objects
                (and (some? new-shape-ref) (not= new-shape-ref (:shape-ref shape)))
                (assoc-in [(:id shape) :shape-ref] new-shape-ref))))]



    (let [file-id  (:id file)
          frame-id (cfh/common-parent-frame objects selected)


          initial  {:type :copied-shapes
                    :features features
                    :version version
                    :file-id file-id
                    :selected selected
                    :objects {}
                    :images #{}
                    :in-viewport false}

          shapes   (->> (cfh/selected-with-children objects selected)
                        (keep (d/getf objects)))]

      (->> shapes
           (map (partial prepare-object objects frame-id))
           (reduce collect-data initial)
           sort-selected
           advance-copies))))

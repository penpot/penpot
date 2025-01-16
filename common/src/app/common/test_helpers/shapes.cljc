;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.test-helpers.shapes
  (:require
   [app.common.colors :as clr]
   [app.common.files.helpers :as cfh]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.types.color :as ctc]
   [app.common.types.colors-list :as ctcl]
   [app.common.types.container :as ctn]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.types.typographies-list :as cttl]
   [app.common.types.typography :as ctt]))

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
    (update file :data
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
  (let [page     (if page-label
                   (thf/get-page file page-label)
                   (thf/current-page file))
        shape-id (thi/id label)]
    (ctst/get-shape page shape-id)))

(defn get-shape-by-id
  [file id & {:keys [page-label]}]
  (let [page (if page-label
               (thf/get-page file page-label)
               (thf/current-page file))]
    (ctst/get-shape page id)))

(defn update-shape
  [file shape-label attr val & {:keys [page-label]}]
  (let [page (if page-label
               (thf/get-page file page-label)
               (thf/current-page file))
        shape (ctst/get-shape page (thi/id shape-label))]
    (update file :data
            (fn [file-data]
              (ctpl/update-page file-data
                                (:id page)
                                #(ctst/set-shape % (ctn/set-shape-attr shape attr val)))))))

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
    (update file :data ctcl/add-color color)))

(defn sample-typography
  [label & {:keys [] :as params}]
  (ctt/make-typography (assoc params :id (thi/new-id! label))))

(defn add-sample-typography
  [file label & {:keys [] :as params}]
  (let [typography (sample-typography label params)]
    (update file :data cttl/add-typography typography)))

(defn add-interaction
  [file origin-label dest-label]
  (let [page         (thf/current-page file)
        origin       (get-shape file origin-label)
        dest         (get-shape file dest-label)
        interaction  (-> ctsi/default-interaction
                         (ctsi/set-destination (:id dest))
                         (assoc :position-relative-to (:id origin)))
        interactions (ctsi/add-interaction (:interactions origin) interaction)]
    (update file :data
            (fn [file-data]
              (ctpl/update-page file-data
                                (:id page)
                                #(ctst/set-shape % (assoc origin :interactions interactions)))))))

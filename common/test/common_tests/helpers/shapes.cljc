;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.helpers.shapes
  (:require
   [app.common.colors :as clr]
   [app.common.files.helpers :as cfh]
   [app.common.types.color :as ctc]
   [app.common.types.colors-list :as ctcl]
   [app.common.types.file :as ctf]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.typographies-list :as cttl]
   [app.common.types.typography :as ctt]
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

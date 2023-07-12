;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.helpers.files
  (:require
   [app.common.files.features :as ffeat]
   [app.common.geom.point :as gpt]
   [app.common.types.colors-list :as ctcl]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.typographies-list :as ctyl]
   [app.common.uuid :as uuid]))

(defn- make-file-data
  [file-id page-id]
  (binding [ffeat/*current* #{"components/v2"}]
    (ctf/make-file-data file-id page-id)))

(def ^:private idmap (atom {}))

(defn reset-idmap!
  [next]
  (reset! idmap {})
  (next))

(defn id
  [label]
  (get @idmap label))

(defn sample-file
  ([file-id page-id] (sample-file file-id page-id nil))
  ([file-id page-id props]
   (merge {:id file-id
           :name (get props :name "File1")
           :data (make-file-data file-id page-id)}
          props)))

(defn sample-shape
  [file label type page-id props]
  (ctf/update-file-data
    file
    (fn [file-data]
      (let [frame-id  (get props :frame-id uuid/zero)
            parent-id (get props :parent-id uuid/zero)
            shape     (cts/setup-shape
                       (-> {:type type
                            :width 1
                            :height 1}
                           (merge props)))]

        (swap! idmap assoc label (:id shape))
        (ctpl/update-page file-data
                          page-id
                          #(ctst/add-shape (:id shape)
                                           shape
                                           %
                                           frame-id
                                           parent-id
                                           0
                                           true))))))

(defn sample-component
  [file label page-id shape-id]
  (ctf/update-file-data
    file
    (fn [file-data]
      (let [page (ctpl/get-page file-data page-id)

            [component-shape component-shapes updated-shapes]
            (ctn/make-component-shape (ctn/get-shape page shape-id)
                                      (:objects page)
                                      (:id file)
                                      true)]

        (swap! idmap assoc label (:id component-shape))
        (-> file-data
            (ctpl/update-page page-id
                              #(reduce (fn [page shape] (ctst/set-shape page shape))
                                       %
                                       updated-shapes))
            (ctkl/add-component {:id (:id component-shape)
                                 :name (:name component-shape)
                                 :path ""
                                 :main-instance-id shape-id
                                 :main-instance-page page-id
                                 :shapes component-shapes}))))))

(defn sample-instance
  [file label page-id library component-id]
  (ctf/update-file-data
    file
    (fn [file-data]
      (let [[instance-shape instance-shapes]
            (ctn/make-component-instance (ctpl/get-page file-data page-id)
                                         (ctkl/get-component (:data library) component-id)
                                         (:data library)
                                         (gpt/point 0 0)
                                         true)]

        (swap! idmap assoc label (:id instance-shape))
        (-> file-data
            (ctpl/update-page page-id
                              #(reduce (fn [page shape]
                                         (ctst/add-shape (:id shape)
                                                         shape
                                                         page
                                                         uuid/zero
                                                         (:parent-id shape)
                                                         0
                                                         true))
                                       %
                                       instance-shapes)))))))

(defn sample-color
  [file label props]
  (ctf/update-file-data
    file
    (fn [file-data]
      (let [id (uuid/next)
            props (merge {:id id
                          :name "Color 1"
                          :color "#000000"
                          :opacity 1}
                         props)]
        (swap! idmap assoc label id)
        (ctcl/add-color file-data props)))))

(defn sample-typography
  [file label props]
  (ctf/update-file-data
    file
    (fn [file-data]
      (let [id (uuid/next)
            props (merge {:id id
                          :name "Typography 1"
                          :font-id "sourcesanspro"
                          :font-family "sourcesanspro"
                          :font-size "14"
                          :font-style "normal"
                          :font-variant-id "regular"
                          :font-weight "400"
                          :line-height "1.2"
                          :letter-spacing "0"
                          :text-transform "none"}
                         props)]
        (swap! idmap assoc label id)
        (ctyl/add-typography file-data props)))))


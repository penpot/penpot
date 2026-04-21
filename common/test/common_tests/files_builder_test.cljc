;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.files-builder-test
  (:require
   [app.common.files.builder :as fb]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(defn- stroke
  [color]
  [{:stroke-style :solid
    :stroke-alignment :inner
    :stroke-width 1
    :stroke-color color
    :stroke-opacity 1}])

(t/deftest add-bool-uses-difference-head-style
  (let [file-id  (uuid/next)
        page-id  (uuid/next)
        group-id (uuid/next)
        child-a  (uuid/next)
        child-b  (uuid/next)
        state    (-> (fb/create-state)
                     (fb/add-file {:id file-id :name "Test file"})
                     (fb/add-page {:id page-id :name "Page 1"})
                     (fb/add-group {:id group-id :name "Group A"})
                     (fb/add-shape {:id child-a
                                    :type :rect
                                    :name "A"
                                    :x 0
                                    :y 0
                                    :width 10
                                    :height 10
                                    :strokes (stroke "#ff0000")})
                     (fb/add-shape {:id child-b
                                    :type :rect
                                    :name "B"
                                    :x 20
                                    :y 0
                                    :width 10
                                    :height 10
                                    :strokes (stroke "#00ff00")})
                     (fb/close-group)
                     (fb/add-bool {:group-id group-id
                                   :type :difference}))
        bool     (fb/get-shape state group-id)]
    (t/is (= :bool (:type bool)))
    (t/is (= (stroke "#ff0000") (:strokes bool)))))

(t/deftest add-file-media-validates-and-persists-media
  (let [file-id  (uuid/next)
        page-id  (uuid/next)
        image-id (uuid/next)
        state    (-> (fb/create-state)
                     (fb/add-file {:id file-id :name "Test file"})
                     (fb/add-page {:id page-id :name "Page 1"})
                     (fb/add-file-media {:id image-id
                                         :name "Image"
                                         :width 128
                                         :height 64}
                                        (fb/map->BlobWrapper {:mtype "image/png"
                                                              :size 42
                                                              :blob nil})))
        media    (get-in state [::fb/file-media image-id])]
    (t/is (= image-id (::fb/last-id state)))
    (t/is (= "Image" (:name media)))
    (t/is (= 128 (:width media)))
    (t/is (= 64 (:height media)))))

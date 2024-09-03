;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.util-snap-data-test
  (:require
   [app.common.files.builder :as fb]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.util.snap-data :as sd]
   [cljs.pprint :refer [pprint]]
   [cljs.test :as t :include-macros true]))

(t/deftest test-create-index
  (t/testing "Create empty data"
    (let [data (sd/make-snap-data)]
      (t/is (some? data))))

  (t/testing "Add empty page (only root-frame)"
    (let [page (-> (fb/create-file "Test")
                   (fb/add-page {:name "Page 1"})
                   (fb/get-current-page))

          data (-> (sd/make-snap-data)
                   (sd/add-page page))]
      (t/is (some? data))))

  (t/testing "Create simple shape on root"
    (let [file (-> (fb/create-file "Test")
                   (fb/add-page {:name "Page 1"})
                   (fb/create-rect
                    {:x 0
                     :y 0
                     :width 100
                     :height 100}))
          page (fb/get-current-page file)

          data (-> (sd/make-snap-data)
                   (sd/add-page page))

          result-x (sd/query data (:id page) uuid/zero :x [0 100])]

      (t/is (some? data))

      ;; 3 = left side, center and right side
      (t/is (= (count result-x) 3))

      ;; Left side: two points
      (t/is (= (first (nth result-x 0)) 0))

      ;; Center one point
      (t/is (= (first (nth result-x 1)) 50))

      ;; Right side two points
      (t/is (= (first (nth result-x 2)) 100))))

  (t/testing "Add page with single empty frame"
    (let [file (-> (fb/create-file "Test")
                   (fb/add-page {:name "Page 1"})
                   (fb/add-artboard
                    {:x 0
                     :y 0
                     :width 100
                     :height 100})
                   (fb/close-artboard))

          frame-id (:last-id file)
          page     (fb/get-current-page file)

          ;; frame-id (:last-id file)
          data (-> (sd/make-snap-data)
                   (sd/add-page page))

          result-zero-x (sd/query data (:id page) uuid/zero :x [0 100])
          result-frame-x (sd/query data (:id page) frame-id :x [0 100])]

      (t/is (some? data))
      (t/is (= (count result-zero-x) 3))
      (t/is (= (count result-frame-x) 3))))

  (t/testing "Add page with some shapes inside frames"
    (let [file (-> (fb/create-file "Test")
                   (fb/add-page {:name "Page 1"})
                   (fb/add-artboard
                    {:x 0
                     :y 0
                     :width 100
                     :height 100}))
          frame-id (:last-id file)

          file (-> file
                   (fb/create-rect
                    {:x 25
                     :y 25
                     :width 50
                     :height 50})
                   (fb/close-artboard))

          page     (fb/get-current-page file)

          ;; frame-id (:last-id file)
          data (-> (sd/make-snap-data)
                   (sd/add-page page))

          result-zero-x (sd/query data (:id page) uuid/zero :x [0 100])
          result-frame-x (sd/query data (:id page) frame-id :x [0 100])]

      (t/is (some? data))
      (t/is (= (count result-zero-x) 3))
      (t/is (= (count result-frame-x) 5))))

  (t/testing "Add a global guide"
    (let [file (-> (fb/create-file "Test")
                   (fb/add-page {:name "Page 1"})
                   (fb/add-guide {:position 50 :axis :x})
                   (fb/add-artboard {:x 200 :y 200 :width 100 :height 100})
                   (fb/close-artboard))

          frame-id (:last-id file)
          page     (fb/get-current-page file)

          ;; frame-id (:last-id file)
          data (-> (sd/make-snap-data)
                   (sd/add-page page))

          result-zero-x (sd/query data (:id page) uuid/zero :x [0 100])
          result-zero-y (sd/query data (:id page) uuid/zero :y [0 100])
          result-frame-x (sd/query data (:id page) frame-id :x [0 100])
          result-frame-y (sd/query data (:id page) frame-id :y [0 100])]

      (t/is (some? data))
      ;; We can snap in the root
      (t/is (= (count result-zero-x) 1))
      (t/is (= (count result-zero-y) 0))

      ;; We can snap in the frame
      (t/is (= (count result-frame-x) 1))
      (t/is (= (count result-frame-y) 0))))

  (t/testing "Add a frame guide"
    (let [file (-> (fb/create-file "Test")
                   (fb/add-page {:name "Page 1"})
                   (fb/add-artboard {:x 200 :y 200 :width 100 :height 100})
                   (fb/close-artboard))

          frame-id (:last-id file)

          file (-> file
                   (fb/add-guide {:position 50 :axis :x :frame-id frame-id}))

          page     (fb/get-current-page file)

          ;; frame-id (:last-id file)
          data (-> (sd/make-snap-data)
                   (sd/add-page page))

          result-zero-x (sd/query data (:id page) uuid/zero :x [0 100])
          result-zero-y (sd/query data (:id page) uuid/zero :y [0 100])
          result-frame-x (sd/query data (:id page) frame-id :x [0 100])
          result-frame-y (sd/query data (:id page) frame-id :y [0 100])]
      (t/is (some? data))
      ;; We can snap in the root
      (t/is (= (count result-zero-x) 0))
      (t/is (= (count result-zero-y) 0))

      ;; We can snap in the frame
      (t/is (= (count result-frame-x) 1))
      (t/is (= (count result-frame-y) 0)))))

(t/deftest test-update-index
  (t/testing "Create frame on root and then remove it."
    (let [file (-> (fb/create-file "Test")
                   (fb/add-page {:name "Page 1"})
                   (fb/add-artboard
                    {:x 0
                     :y 0
                     :width 100
                     :height 100})
                   (fb/close-artboard))

          shape-id (:last-id file)
          page     (fb/get-current-page file)

          ;; frame-id (:last-id file)
          data     (-> (sd/make-snap-data)
                       (sd/add-page page))

          file     (-> file
                       (fb/delete-object shape-id))

          new-page (fb/get-current-page file)
          data     (sd/update-page data page new-page)

          result-x (sd/query data (:id page) uuid/zero :x [0 100])
          result-y (sd/query data (:id page) uuid/zero :y [0 100])]

      (t/is (some? data))
      (t/is (= (count result-x) 0))
      (t/is (= (count result-y) 0))))

  (t/testing "Create simple shape on root. Then remove it"
    (let [file (-> (fb/create-file "Test")
                   (fb/add-page {:name "Page 1"})
                   (fb/create-rect
                    {:x 0
                     :y 0
                     :width 100
                     :height 100}))

          shape-id (:last-id file)
          page (fb/get-current-page file)

          ;; frame-id (:last-id file)
          data (-> (sd/make-snap-data)
                   (sd/add-page page))

          file (fb/delete-object file shape-id)

          new-page (fb/get-current-page file)
          data (sd/update-page data page new-page)

          result-x (sd/query data (:id page) uuid/zero :x [0 100])
          result-y (sd/query data (:id page) uuid/zero :y [0 100])]

      (t/is (some? data))
      (t/is (= (count result-x) 0))
      (t/is (= (count result-y) 0))))

  (t/testing "Create shape inside frame, then remove it"
    (let [file (-> (fb/create-file "Test")
                   (fb/add-page {:name "Page 1"})
                   (fb/add-artboard
                    {:x 0
                     :y 0
                     :width 100
                     :height 100}))
          frame-id (:last-id file)

          file (fb/create-rect file {:x 25 :y 25 :width 50 :height 50})
          shape-id (:last-id file)

          file (fb/close-artboard file)

          page (fb/get-current-page file)
          data (-> (sd/make-snap-data)
                   (sd/add-page page))

          file (fb/delete-object file shape-id)
          new-page (fb/get-current-page file)

          data (sd/update-page data page new-page)

          result-zero-x (sd/query data (:id page) uuid/zero :x [0 100])
          result-frame-x (sd/query data (:id page) frame-id :x [0 100])]

      (t/is (some? data))
      (t/is (= (count result-zero-x) 3))
      (t/is (= (count result-frame-x) 3))))

  (t/testing "Create global guide then remove it"
    (let [file (-> (fb/create-file "Test")
                   (fb/add-page {:name "Page 1"})
                   (fb/add-guide {:position 50 :axis :x}))

          guide-id (:last-id file)

          file (-> (fb/add-artboard file {:x 200 :y 200 :width 100 :height 100})
                   (fb/close-artboard))

          frame-id (:last-id file)
          page     (fb/get-current-page file)
          data (-> (sd/make-snap-data) (sd/add-page page))

          new-page (-> (fb/delete-guide file guide-id)
                       (fb/get-current-page))

          data (sd/update-page data page new-page)

          result-zero-x (sd/query data (:id page) uuid/zero :x [0 100])
          result-zero-y (sd/query data (:id page) uuid/zero :y [0 100])
          result-frame-x (sd/query data (:id page) frame-id :x [0 100])
          result-frame-y (sd/query data (:id page) frame-id :y [0 100])]

      (t/is (some? data))
      ;; We can snap in the root
      (t/is (= (count result-zero-x) 0))
      (t/is (= (count result-zero-y) 0))

      ;; We can snap in the frame
      (t/is (= (count result-frame-x) 0))
      (t/is (= (count result-frame-y) 0))))

  (t/testing "Create frame guide then remove it"
    (let [file (-> (fb/create-file "Test")
                   (fb/add-page {:name "Page 1"})
                   (fb/add-artboard {:x 200 :y 200 :width 100 :height 100})
                   (fb/close-artboard))

          frame-id (:last-id file)
          file (fb/add-guide file {:position 50 :axis :x :frame-id frame-id})
          guide-id (:last-id file)

          page     (fb/get-current-page file)
          data (-> (sd/make-snap-data) (sd/add-page page))

          new-page (-> (fb/delete-guide file guide-id)
                       (fb/get-current-page))

          data (sd/update-page data page new-page)

          result-zero-x (sd/query data (:id page) uuid/zero :x [0 100])
          result-zero-y (sd/query data (:id page) uuid/zero :y [0 100])
          result-frame-x (sd/query data (:id page) frame-id :x [0 100])
          result-frame-y (sd/query data (:id page) frame-id :y [0 100])]
      (t/is (some? data))
      ;; We can snap in the root
      (t/is (= (count result-zero-x) 0))
      (t/is (= (count result-zero-y) 0))

      ;; We can snap in the frame
      (t/is (= (count result-frame-x) 0))
      (t/is (= (count result-frame-y) 0))))

  (t/testing "Update frame coordinates"
    (let [file (-> (fb/create-file "Test")
                   (fb/add-page {:name "Page 1"})
                   (fb/add-artboard
                    {:x 0
                     :y 0
                     :width 100
                     :height 100})
                   (fb/close-artboard))

          frame-id  (:last-id file)
          page      (fb/get-current-page file)
          data      (-> (sd/make-snap-data) (sd/add-page page))

          frame     (fb/lookup-shape file frame-id)
          new-frame (-> frame
                        (dissoc :selrect :points)
                        (assoc :x 200 :y 200)
                        (cts/setup-shape))

          file      (fb/update-object file frame new-frame)
          new-page  (fb/get-current-page file)

          data      (sd/update-page data page new-page)

          result-zero-x-1 (sd/query data (:id page) uuid/zero :x [0 100])
          result-frame-x-1 (sd/query data (:id page) frame-id :x [0 100])
          result-zero-x-2 (sd/query data (:id page) uuid/zero :x [200 300])
          result-frame-x-2 (sd/query data (:id page) frame-id :x [200 300])]

      (t/is (some? data))
      (t/is (= (count result-zero-x-1) 0))
      (t/is (= (count result-frame-x-1) 0))
      (t/is (= (count result-zero-x-2) 3))
      (t/is (= (count result-frame-x-2) 3))))

  (t/testing "Update shape coordinates"
    (let [file (-> (fb/create-file "Test")
                   (fb/add-page {:name "Page 1"})
                   (fb/create-rect
                    {:x 0
                     :y 0
                     :width 100
                     :height 100}))

          shape-id (:last-id file)
          page     (fb/get-current-page file)
          data (-> (sd/make-snap-data) (sd/add-page page))

          shape (fb/lookup-shape file shape-id)
          new-shape (-> shape
                        (dissoc :selrect :points)
                        (assoc :x 200 :y 200))

          file (fb/update-object file shape new-shape)
          new-page (fb/get-current-page file)

          data (sd/update-page data page new-page)

          result-zero-x-1 (sd/query data (:id page) uuid/zero :x [0 100])
          result-zero-x-2 (sd/query data (:id page) uuid/zero :x [200 300])]

      (t/is (some? data))
      (t/is (= (count result-zero-x-1) 0))
      (t/is (= (count result-zero-x-2) 3))))

  (t/testing "Update global guide"
    (let [guide {:position 50 :axis :x}
          file  (-> (fb/create-file "Test")
                    (fb/add-page {:name "Page 1"})
                    (fb/add-guide guide))

          guide-id (:last-id file)
          guide (assoc guide :id guide-id)

          file (-> (fb/add-artboard file {:x 500 :y 500 :width 100 :height 100})
                   (fb/close-artboard))

          frame-id (:last-id file)
          page     (fb/get-current-page file)
          data     (-> (sd/make-snap-data) (sd/add-page page))

          new-page (-> (fb/update-guide file (assoc guide :position 150))
                       (fb/get-current-page))

          data (sd/update-page data page new-page)

          result-zero-x-1 (sd/query data (:id page) uuid/zero :x [0 100])
          result-zero-y-1 (sd/query data (:id page) uuid/zero :y [0 100])
          result-frame-x-1 (sd/query data (:id page) frame-id :x [0 100])
          result-frame-y-1 (sd/query data (:id page) frame-id :y [0 100])

          result-zero-x-2 (sd/query data (:id page) uuid/zero :x [0 200])
          result-zero-y-2 (sd/query data (:id page) uuid/zero :y [0 200])
          result-frame-x-2 (sd/query data (:id page) frame-id :x [0 200])
          result-frame-y-2 (sd/query data (:id page) frame-id :y [0 200])]

      (t/is (some? data))

      (t/is (= (count result-zero-x-1) 0))
      (t/is (= (count result-zero-y-1) 0))
      (t/is (= (count result-frame-x-1) 0))
      (t/is (= (count result-frame-y-1) 0))

      (t/is (= (count result-zero-x-2) 1))
      (t/is (= (count result-zero-y-2) 0))
      (t/is (= (count result-frame-x-2) 1))
      (t/is (= (count result-frame-y-2) 0)))))

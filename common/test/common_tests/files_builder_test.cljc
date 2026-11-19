;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files-builder-test
  (:require
   [app.common.files.builder :as fb]
   [app.common.files.validate :as cfv]
   [app.common.path-names :as cpn]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(defn- stroke
  [color]
  [{:stroke-style :solid
    :stroke-alignment :inner
    :stroke-width 1
    :stroke-color color
    :stroke-opacity 1}])

(t/deftest add-shape-normalizes-svg-defs-and-content-to-kebab
  ;; Defense in depth: generic producers that bypass `shapes-builder`
  ;; still land on the stored kebab convention for defs and content.
  (let [file-id  (uuid/next)
        page-id  (uuid/next)
        shape-id (uuid/next)
        state    (-> (fb/create-state)
                     (fb/add-file {:id file-id :name "Test file"})
                     (fb/add-page {:id page-id :name "Page 1"})
                     (fb/add-shape {:id shape-id
                                    :type :svg-raw
                                    :name "raw"
                                    :x 0
                                    :y 0
                                    :width 10
                                    :height 10
                                    :content {:tag :g
                                              :attrs {:strokeWidth "2"
                                                      :data-foo "keep-me"}
                                              :content []}
                                    :svg-attrs {:fillRule "evenodd"}
                                    :svg-defs {"g1" {:tag :linearGradient
                                                     :attrs {:id "g1"
                                                             :gradientUnits "userSpaceOnUse"}
                                                     :content []}}}))
        shape    (fb/get-shape state shape-id)
        node     (get (:svg-defs shape) "g1")]
    (t/is (= "evenodd" (get-in shape [:svg-attrs :fill-rule])))
    (t/is (= "userSpaceOnUse" (get-in node [:attrs :gradient-units])))
    (t/is (= "2" (get-in shape [:content :attrs :stroke-width])))
    (t/is (= "keep-me" (get-in shape [:content :attrs :data-foo]))
          "unknown content keys survive")))

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

(t/deftest add-shape-normalizes-path-name
  (let [file-id (uuid/next)
        page-id (uuid/next)
        shape-id (uuid/next)
        state (-> (fb/create-state)
                  (fb/add-file {:id file-id :name "Test file"})
                  (fb/add-page {:id page-id :name "Page 1"})
                  (fb/add-shape {:id shape-id
                                 :type :frame
                                 :name "Group/Subgroup   /   Name"
                                 :x 0
                                 :y 0
                                 :width 10
                                 :height 10}))
        shape (fb/get-shape state shape-id)]
    (t/is (= "Group / Subgroup / Name" (:name shape)))))

(t/deftest add-shape-uniquifies-default-names
  (let [file-id  (uuid/next)
        page-id  (uuid/next)
        board-a  (uuid/next)
        board-b  (uuid/next)
        state    (-> (fb/create-state)
                     (fb/add-file {:id file-id :name "Test file"})
                     (fb/add-page {:id page-id :name "Page 1"})
                     (fb/add-board {:id board-a :x 0 :y 0 :width 10 :height 10})
                     (fb/add-board {:id board-b :x 20 :y 0 :width 10 :height 10}))]
    (t/is (= "Board" (:name (fb/get-shape state board-a))))
    (t/is (= "Board-1" (:name (fb/get-shape state board-b))))))

(t/deftest add-shape-uniquifies-names-that-clean-to-the-same-path
  (let [file-id  (uuid/next)
        page-id  (uuid/next)
        shape-a  (uuid/next)
        shape-b  (uuid/next)
        state    (-> (fb/create-state)
                     (fb/add-file {:id file-id :name "Test file"})
                     (fb/add-page {:id page-id :name "Page 1"})
                     (fb/add-shape {:id shape-a :type :frame :name "Group/Name"
                                    :x 0 :y 0 :width 10 :height 10})
                     (fb/add-shape {:id shape-b :type :frame :name "Group  /  Name"
                                    :x 20 :y 0 :width 10 :height 10}))]
    (t/is (= "Group / Name" (:name (fb/get-shape state shape-a))))
    (t/is (= "Group / Name-1" (:name (fb/get-shape state shape-b))))))

(t/deftest add-shape-uniquifies-names-used-by-component-roots
  (let [file-id      (uuid/next)
        page-id      (uuid/next)
        frame-id     (uuid/next)
        component-id (uuid/next)
        shape-id     (uuid/next)
        state        (-> (fb/create-state)
                         (fb/add-file {:id file-id :name "Test file"})
                         (fb/add-page {:id page-id :name "Page 1"})
                         (fb/add-board {:id frame-id :name "Draft"
                                        :x 0 :y 0 :width 10 :height 10})
                         (fb/add-component {:component-id component-id
                                            :name "Button"
                                            :path "Forms"})
                         (fb/close-board)
                         (fb/add-shape {:id shape-id :type :frame :name "Forms/Button"
                                        :x 20 :y 0 :width 10 :height 10}))]
    (t/is (= "Forms / Button" (:name (fb/get-shape state frame-id))))
    (t/is (= "Forms / Button-1" (:name (fb/get-shape state shape-id))))))

(t/deftest add-shape-keeps-unique-names-per-page
  (let [file-id  (uuid/next)
        page-a   (uuid/next)
        page-b   (uuid/next)
        board-a  (uuid/next)
        board-b  (uuid/next)
        state    (-> (fb/create-state)
                     (fb/add-file {:id file-id :name "Test file"})
                     (fb/add-page {:id page-a :name "Page 1"})
                     (fb/add-board {:id board-a :x 0 :y 0 :width 10 :height 10})
                     (fb/add-page {:id page-b :name "Page 2"})
                     (fb/add-board {:id board-b :x 0 :y 0 :width 10 :height 10}))
        name-of  (fn [page-id shape-id]
                   (get-in state [::fb/files file-id :data :pages-index
                                  page-id :objects shape-id :name]))]
    (t/is (= "Board" (name-of page-a board-a)))
    (t/is (= "Board" (name-of page-b board-b)))))

(t/deftest add-component-without-name-is-anonymous
  (let [file-id      (uuid/next)
        page-id      (uuid/next)
        frame-id     (uuid/next)
        component-id (uuid/next)
        state        (-> (fb/create-state)
                         (fb/add-file {:id file-id :name "Test file"})
                         (fb/add-page {:id page-id :name "Page 1"})
                         (fb/add-board {:id frame-id :name "Draft"
                                        :x 0 :y 0 :width 10 :height 10})
                         (fb/add-component {:component-id component-id}))
        component    (get-in state [::fb/files file-id :data
                                    :components component-id])]
    (t/is (= "anonymous" (:name component)))
    (t/is (= "anonymous" (:name (fb/get-shape state frame-id))))))

(t/deftest add-component-renames-frame-to-match-component
  (t/testing "the main instance frame name is aligned with the component's path+name"
    (let [file-id      (uuid/next)
          page-id      (uuid/next)
          frame-id     (uuid/next)
          component-id (uuid/next)
          state        (-> (fb/create-state)
                           (fb/add-file {:id file-id :name "Test file"})
                           (fb/add-page {:id page-id :name "Page 1"})
                           (fb/add-board {:id frame-id
                                          :name "Wrong / Frame / Name"
                                          :x 0
                                          :y 0
                                          :width 10
                                          :height 10})
                           (fb/add-component {:component-id component-id
                                              :name "Component"
                                              :path "Group / Subgroup"}))
          shape        (fb/get-shape state frame-id)
          file-data    (get-in state [::fb/files file-id :data])
          component    (get-in file-data [:components component-id])
          errors       (cfv/validate-file {:id file-id :data file-data} {})]

      (t/is (= "Group / Subgroup / Component" (:name shape)))
      (t/is (= (:name shape) (cpn/merge-path-item (:path component) (:name component))))
      (t/is (nil? errors)))))

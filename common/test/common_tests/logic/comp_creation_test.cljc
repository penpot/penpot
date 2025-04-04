;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.logic.comp-creation-test
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.shapes-helpers :as cfsh]
   [app.common.geom.point :as gpt]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.shape-tree :as ctst]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest test-add-component-from-single-frame
  (let [;; ==== Setup
        file   (-> (thf/sample-file :file1)
                   (ths/add-sample-shape :frame1 :type :frame))

        page   (thf/current-page file)
        frame1 (ths/get-shape file :frame1)

        ;; ==== Action
        [_ component-id changes]
        (cll/generate-add-component (pcb/empty-changes)
                                    [frame1]
                                    (:objects page)
                                    (:id page)
                                    (:id file)
                                    nil)

        file' (thf/apply-changes file changes)

        ;; ==== Get
        component (thc/get-component-by-id file' component-id)
        root      (ths/get-shape-by-id file' (:main-instance-id component))
        frame1'   (ths/get-shape file' :frame1)]

    ;; ==== Check
    (t/is (some? component))
    (t/is (some? root))
    (t/is (some? frame1'))
    (t/is (= (:id root) (:id frame1')))
    (t/is (ctk/main-instance? root))
    (t/is (ctk/main-instance-of? (:id root) (:id page) component))))

(t/deftest test-add-component-from-single-shape
  (let [;; ==== Setup
        file   (-> (thf/sample-file :file1)
                   (ths/add-sample-shape :shape1 :type :rect))

        page   (thf/current-page file)
        shape1 (ths/get-shape file :shape1)

        ;; ==== Action
        [_ component-id changes]
        (cll/generate-add-component (pcb/empty-changes)
                                    [shape1]
                                    (:objects page)
                                    (:id page)
                                    (:id file)
                                    cfsh/prepare-create-artboard-from-selection)

        file' (thf/apply-changes file changes)

        ;; ==== Get
        component (thc/get-component-by-id file' component-id)
        root      (ths/get-shape-by-id file' (:main-instance-id component))
        shape1'   (ths/get-shape file' :shape1)]

    ;; ==== Check
    (t/is (some? component))
    (t/is (some? root))
    (t/is (some? shape1'))
    (t/is (ctst/parent-of? root shape1'))
    (t/is (= (:type root) :frame))
    (t/is (ctk/main-instance? root))
    (t/is (ctk/main-instance-of? (:id root) (:id page) component))))

(t/deftest test-add-component-from-several-shapes
  (let [;; ==== Setup
        file   (-> (thf/sample-file :file1)
                   (ths/add-sample-shape :shape1 :type :rect)
                   (ths/add-sample-shape :shape2 :type :rect))

        page   (thf/current-page file)
        shape1 (ths/get-shape file :shape1)
        shape2 (ths/get-shape file :shape2)

        ;; ==== Action
        [_ component-id changes]
        (cll/generate-add-component (pcb/empty-changes)
                                    [shape1 shape2]
                                    (:objects page)
                                    (:id page)
                                    (:id file)
                                    cfsh/prepare-create-artboard-from-selection)

        file' (thf/apply-changes file changes)

        ;; ==== Get
        component (thc/get-component-by-id file' component-id)
        root      (ths/get-shape-by-id file' (:main-instance-id component))
        shape1'   (ths/get-shape file' :shape1)
        shape2'   (ths/get-shape file' :shape2)]

    ;; ==== Check
    (t/is (some? component))
    (t/is (some? root))
    (t/is (some? shape1'))
    (t/is (some? shape2'))
    (t/is (ctst/parent-of? root shape1'))
    (t/is (ctst/parent-of? root shape2'))
    (t/is (= (:type root) :frame))
    (t/is (ctk/main-instance? root))
    (t/is (ctk/main-instance-of? (:id root) (:id page) component))))

(t/deftest test-add-component-from-several-frames
  (let [;; ==== Setup
        file   (-> (thf/sample-file :file1)
                   (ths/add-sample-shape :frame1 :type :frame)
                   (ths/add-sample-shape :frame2 :type :frame))

        page   (thf/current-page file)
        frame1 (ths/get-shape file :frame1)
        frame2 (ths/get-shape file :frame2)

        ;; ==== Action
        [_ component-id changes]
        (cll/generate-add-component (pcb/empty-changes)
                                    [frame1 frame2]
                                    (:objects page)
                                    (:id page)
                                    (:id file)
                                    cfsh/prepare-create-artboard-from-selection)

        file' (thf/apply-changes file changes)

        ;; ==== Get
        component (thc/get-component-by-id file' component-id)
        root      (ths/get-shape-by-id file' (:main-instance-id component))
        frame1'   (ths/get-shape file' :frame1)
        frame2'   (ths/get-shape file' :frame2)]

    ;; ==== Check
    (t/is (some? component))
    (t/is (some? root))
    (t/is (some? frame1'))
    (t/is (some? frame2'))
    (t/is (ctst/parent-of? root frame1'))
    (t/is (ctst/parent-of? root frame2'))
    (t/is (= (:type root) :frame))
    (t/is (ctk/main-instance? root))
    (t/is (ctk/main-instance-of? (:id root) (:id page) component))))

(t/deftest test-add-component-from-frame-with-children
  (let [;; ==== Setup
        file   (-> (thf/sample-file :file1)
                   (ths/add-sample-shape :frame1 :type :frame)
                   (ths/add-sample-shape :shape1 :type :rect :parent-label :frame1)
                   (ths/add-sample-shape :shape2 :type :rect :parent-label :frame1))

        page   (thf/current-page file)
        frame1 (ths/get-shape file :frame1)

        ;; ==== Action
        [_ component-id changes]
        (cll/generate-add-component (pcb/empty-changes)
                                    [frame1]
                                    (:objects page)
                                    (:id page)
                                    (:id file)
                                    nil)

        file' (thf/apply-changes file changes)

        ;; ==== Get
        component (thc/get-component-by-id file' component-id)
        root      (ths/get-shape-by-id file' (:main-instance-id component))
        frame1'   (ths/get-shape file' :frame1)
        shape1'   (ths/get-shape file' :shape1)
        shape2'   (ths/get-shape file' :shape2)]

    ;; ==== Check
    (t/is (some? component))
    (t/is (some? root))
    (t/is (some? frame1'))
    (t/is (= (:id root) (:id frame1')))
    (t/is (ctst/parent-of? frame1' shape1'))
    (t/is (ctst/parent-of? frame1' shape2'))
    (t/is (ctk/main-instance? root))
    (t/is (ctk/main-instance-of? (:id root) (:id page) component))))

(t/deftest test-add-component-from-copy
  (let [;; ==== Setup
        file   (-> (thf/sample-file :file1)
                   (tho/add-simple-component-with-copy :component1
                                                       :main1-root
                                                       :main1-child
                                                       :copy1-root))

        page       (thf/current-page file)
        copy1-root (ths/get-shape file :copy1-root)

        ;; ==== Action
        [_ component2-id changes]
        (cll/generate-add-component (pcb/empty-changes)
                                    [copy1-root]
                                    (:objects page)
                                    (:id page)
                                    (:id file)
                                    cfsh/prepare-create-artboard-from-selection)

        file' (thf/apply-changes file changes)

        ;; ==== Get
        component2' (thc/get-component-by-id file' component2-id)
        root2'      (ths/get-shape-by-id file' (:main-instance-id component2'))
        copy1-root' (ths/get-shape file' :copy1-root)]

    ;; ==== Check
    (t/is (some? component2'))
    (t/is (some? root2'))
    (t/is (some? copy1-root'))
    (t/is (ctst/parent-of? root2' copy1-root'))
    (t/is (ctk/main-instance? root2'))
    (t/is (ctk/main-instance-of? (:id root2') (:id page) component2'))))

(t/deftest test-rename-component
  (let [;; ==== Setup
        file  (-> (thf/sample-file :file1)
                  (tho/add-simple-component :component1
                                            :main1-root
                                            :main1-child
                                            :name "Test component before"))

        component (thc/get-component file :component1)

        ;; ==== Action
        changes   (cll/generate-rename-component (pcb/empty-changes)
                                                 (:id component)
                                                 "Test component after"
                                                 (:data file))

        file' (thf/apply-changes file changes)

        ;; ==== Get
        component' (thc/get-component file' :component1)]

    ;; ==== Check
    (t/is (= (:name component') "Test component after"))))

(t/deftest test-duplicate-component
  (let [;; ==== Setup
        file  (-> (thf/sample-file :file1)
                  (tho/add-simple-component :component1
                                            :main1-root
                                            :main1-child))

        component (thc/get-component file :component1)

        ;; ==== Action
        [_ changes]
        (cll/generate-duplicate-component (pcb/empty-changes)
                                          file
                                          (:id component)
                                          (uuid/next)
                                          true)

        file'   (thf/apply-changes file changes)

        ;; ==== Get
        components' (ctkl/components-seq (:data file'))
        component1' (d/seek #(= (:id %) (thi/id :component1)) components')
        component2' (d/seek #(not= (:id %) (thi/id :component1)) components')
        root1'      (ths/get-shape-by-id file' (:main-instance-id component1'))
        root2'      (ths/get-shape-by-id file' (:main-instance-id component2'))
        child1'     (ths/get-shape-by-id file' (first (:shapes root1')))
        child2'     (ths/get-shape-by-id file' (first (:shapes root2')))]

    ;; ==== Check
    (t/is (= 2 (count components')))
    (t/is (some? component1'))
    (t/is (some? component2'))
    (t/is (some? root1'))
    (t/is (some? root2'))
    (t/is (= (thi/id :main1-root) (:id root1')))
    (t/is (not= (thi/id :main1-root) (:id root2')))
    (t/is (some? child1'))
    (t/is (some? child2'))
    (t/is (= (thi/id :main1-child) (:id child1')))
    (t/is (not= (thi/id :main1-child) (:id child2')))))

(t/deftest test-delete-component
  (let [;; ==== Setup
        file (-> (thf/sample-file :file1)
                 (tho/add-simple-component-with-copy :component1
                                                     :main1-root
                                                     :main1-child
                                                     :copy1-root))

        page (thf/current-page file)
        root (ths/get-shape file :main1-root)

        ;; ==== Action
        [_ changes]
        (cls/generate-delete-shapes (pcb/empty-changes)
                                    file
                                    page
                                    (:objects page)
                                    #{(:id root)}
                                    {:components-v2 true})

        file' (thf/apply-changes file changes)

        ;; ==== Get
        component1'        (thc/get-component file' :component1 :include-deleted? true)
        copy1-root'        (ths/get-shape file' :copy1-root)

        main1-root'        (ths/get-shape file' :main1-root)
        main1-child'       (ths/get-shape file' :main1-child)

        saved-objects      (:objects component1')
        saved-main1-root'  (get saved-objects (thi/id :main1-root))
        saved-main1-child' (get saved-objects (thi/id :main1-child))]

    ;; ==== Check
    (t/is (true? (:deleted component1')))
    (t/is (some? copy1-root'))
    (t/is (nil? main1-root'))
    (t/is (nil? main1-child'))
    (t/is (some? saved-main1-root'))
    (t/is (some? saved-main1-child'))))

(t/deftest test-restore-component
  (let [;; ==== Setup
        file (-> (thf/sample-file :file1)
                 (tho/add-simple-component-with-copy :component1
                                                     :main1-root
                                                     :main1-child
                                                     :copy1-root))

        page (thf/current-page file)
        root (ths/get-shape file :main1-root)

        ;; ==== Action
        [_ changes]
        (cls/generate-delete-shapes (pcb/empty-changes)
                                    file
                                    page
                                    (:objects page)
                                    #{(:id root)}
                                    {:components-v2 true})

        file-deleted (thf/apply-changes file changes)
        page-deleted (thf/current-page file-deleted)

        changes (cll/generate-restore-component (pcb/empty-changes)
                                                (:data file-deleted)
                                                (thi/id :component1)
                                                (:id file-deleted)
                                                page-deleted
                                                (:objects page-deleted))

        file' (thf/apply-changes file changes)

        ;; ==== Get
        component1'        (thc/get-component file' :component1 :include-deleted? false)
        copy1-root'        (ths/get-shape file' :copy1-root)

        main1-root'        (ths/get-shape file' :main1-root)
        main1-child'       (ths/get-shape file' :main1-child)

        saved-objects'     (:objects component1')]

    ;; ==== Check
    (t/is (nil? (:deleted component1')))
    (t/is (some? copy1-root'))
    (t/is (some? main1-root'))
    (t/is (some? main1-child'))
    (t/is (ctk/main-instance? main1-root'))
    (t/is (ctk/main-instance-of? (:id main1-root') (:id page) component1'))
    (t/is (nil? saved-objects'))))

(t/deftest test-instantiate-component
  (let [;; ==== Setup
        file  (-> (thf/sample-file :file1)
                  (tho/add-simple-component :component1
                                            :main1-root
                                            :main1-child))

        page      (thf/current-page file)
        component (thc/get-component file :component1)

        ;; ==== Action
        [new-shape changes]
        (cll/generate-instantiate-component (-> (pcb/empty-changes nil (:id page))  ;; This may not be moved to generate
                                                (pcb/with-objects (:objects page))) ;; because in some cases the objects
                                            (:objects page)                         ;; not the same as those on the page
                                            (:id file)
                                            (:id component)
                                            (gpt/point 1000 1000)
                                            page
                                            {(:id file) file})

        file' (thf/apply-changes file changes)

        ;; ==== Get
        component'   (thc/get-component file' :component1)
        main1-root'  (ths/get-shape file' :main1-root)
        main1-child' (ths/get-shape file' :main1-child)
        copy1-root'  (ths/get-shape-by-id file' (:id new-shape))
        copy1-child' (ths/get-shape-by-id file' (first (:shapes copy1-root')))]

    ;; ==== Check
    (t/is (some? main1-root'))
    (t/is (some? main1-child'))
    (t/is (some? copy1-root'))
    (t/is (some? copy1-child'))
    (t/is (ctk/instance-root? copy1-root'))
    (t/is (ctk/instance-of? copy1-root' (:id file') (:id component')))
    (t/is (ctk/is-main-of? main1-root' copy1-root'))
    (t/is (ctk/is-main-of? main1-child' copy1-child'))
    (t/is (ctst/parent-of? copy1-root' copy1-child'))))

(t/deftest test-instantiate-component-from-lib
  (let [;; ==== Setup
        library   (-> (thf/sample-file :library1)
                      (tho/add-simple-component :component1
                                                :main1-root
                                                :main1-child))

        file      (thf/sample-file :file1)

        page      (thf/current-page file)
        component (thc/get-component library :component1)

        ;; ==== Action
        [new-shape changes]
        (cll/generate-instantiate-component (-> (pcb/empty-changes nil (:id page))
                                                (pcb/with-objects (:objects page)))
                                            (:objects page)
                                            (:id library)
                                            (:id component)
                                            (gpt/point 1000 1000)
                                            page
                                            {(:id file) file
                                             (:id library) library})

        file' (thf/apply-changes file changes)

        ;; ==== Get
        component'   (thc/get-component library :component1)
        main1-root'  (ths/get-shape library :main1-root)
        main1-child' (ths/get-shape library :main1-child)
        copy1-root'  (ths/get-shape-by-id file' (:id new-shape))
        copy1-child' (ths/get-shape-by-id file' (first (:shapes copy1-root')))]

    ;; ==== Check
    (t/is (some? main1-root'))
    (t/is (some? main1-child'))
    (t/is (some? copy1-root'))
    (t/is (some? copy1-child'))
    (t/is (ctk/instance-root? copy1-root'))
    (t/is (ctk/instance-of? copy1-root' (:id library) (:id component')))
    (t/is (ctk/is-main-of? main1-root' copy1-root'))
    (t/is (ctk/is-main-of? main1-child' copy1-child'))
    (t/is (ctst/parent-of? copy1-root' copy1-child'))))

(t/deftest test-instantiate-nested-component
  (let [;; ==== Setup
        file  (-> (thf/sample-file :file1)
                  (tho/add-nested-component :component1
                                            :main1-root
                                            :main1-child
                                            :component2
                                            :main2-root
                                            :main2-nested-head))

        page      (thf/current-page file)
        component (thc/get-component file :component1)

        ;; ==== Action
        [new-shape changes]
        (cll/generate-instantiate-component (-> (pcb/empty-changes nil (:id page))
                                                (pcb/with-objects (:objects page)))
                                            (:objects page)
                                            (:id file)
                                            (:id component)
                                            (gpt/point 1000 1000)
                                            page
                                            {(:id file) file})

        file' (thf/apply-changes file changes)

        ;; ==== Get
        component'   (thc/get-component file' :component1)
        main1-root'  (ths/get-shape file' :main1-root)
        main1-child' (ths/get-shape file' :main1-child)
        copy1-root'  (ths/get-shape-by-id file' (:id new-shape))
        copy1-child' (ths/get-shape-by-id file' (first (:shapes copy1-root')))]

    ;; ==== Check
    (t/is (some? main1-root'))
    (t/is (some? main1-child'))
    (t/is (some? copy1-root'))
    (t/is (some? copy1-child'))
    (t/is (ctk/instance-root? copy1-root'))
    (t/is (ctk/instance-of? copy1-root' (:id file') (:id component')))
    (t/is (ctk/is-main-of? main1-root' copy1-root'))
    (t/is (ctk/is-main-of? main1-child' copy1-child'))
    (t/is (ctst/parent-of? copy1-root' copy1-child'))))

(t/deftest test-instantiate-nested-component-from-lib
  (let [;; ==== Setup
        library   (-> (thf/sample-file :file1)
                      (tho/add-nested-component :component1
                                                :main1-root
                                                :main1-child
                                                :component2
                                                :main2-root
                                                :main2-nested-head))

        file      (thf/sample-file :file1)

        page      (thf/current-page file)
        component (thc/get-component library :component1)

        ;; ==== Action
        [new-shape changes]
        (cll/generate-instantiate-component (-> (pcb/empty-changes nil (:id page))
                                                (pcb/with-objects (:objects page)))
                                            (:objects page)
                                            (:id library)
                                            (:id component)
                                            (gpt/point 1000 1000)
                                            page
                                            {(:id file) file
                                             (:id library) library})

        file' (thf/apply-changes file changes)

        ;; ==== Get
        component'   (thc/get-component library :component1)
        main1-root'  (ths/get-shape library :main1-root)
        main1-child' (ths/get-shape library :main1-child)
        copy1-root'  (ths/get-shape-by-id file' (:id new-shape))
        copy1-child' (ths/get-shape-by-id file' (first (:shapes copy1-root')))]

    ;; ==== Check
    (t/is (some? main1-root'))
    (t/is (some? main1-child'))
    (t/is (some? copy1-root'))
    (t/is (some? copy1-child'))
    (t/is (ctk/instance-root? copy1-root'))
    (t/is (ctk/instance-of? copy1-root' (:id library) (:id component')))
    (t/is (ctk/is-main-of? main1-root' copy1-root'))
    (t/is (ctk/is-main-of? main1-child' copy1-child'))
    (t/is (ctst/parent-of? copy1-root' copy1-child'))))

(t/deftest test-detach-copy
  (let [;; ==== Setup
        file   (-> (thf/sample-file :file1)
                   (tho/add-simple-component-with-copy :component1
                                                       :main1-root
                                                       :main1-child
                                                       :copy1-root))

        page       (thf/current-page file)
        copy1-root (ths/get-shape file :copy1-root)

        ;; ==== Action
        changes (cll/generate-detach-component (pcb/empty-changes)
                                               (:id copy1-root)
                                               (:data file)
                                               (:id page)
                                               {(:id file) file})

        file' (thf/apply-changes file changes)

        ;; ==== Get
        copy1-root' (ths/get-shape file' :copy1-root)]

    ;; ==== Check
    (t/is (some? copy1-root'))
    (t/is (not (ctk/instance-head? copy1-root')))
    (t/is (not (ctk/in-component-copy? copy1-root')))))

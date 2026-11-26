;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.move-shapes-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.tokens :as tht]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest test-relocate-shape
  (let [;; ==== Setup
        file                   (-> (thf/sample-file :file1)
                                   (tho/add-frame :frame-to-move)
                                   (tho/add-frame :frame-parent))

        page                   (thf/current-page file)
        frame-to-move          (ths/get-shape file :frame-to-move)
        frame-parent           (ths/get-shape file :frame-parent)

        ;; ==== Action

        changes                (cls/generate-relocate (-> (pcb/empty-changes nil)
                                                          (pcb/with-page-id (:id page))
                                                          (pcb/with-objects (:objects page)))
                                                      (:id frame-parent)       ;; parent-id
                                                      0                        ;; to-index
                                                      #{(:id frame-to-move)})  ;; ids

        file'                  (thf/apply-changes file changes)

        ;; ==== Get
        frame-to-move'         (ths/get-shape file' :frame-to-move)
        frame-parent'          (ths/get-shape file' :frame-parent)]

    ;; ==== Check
    ;; frame-to-move has moved
    (t/is (= (:parent-id frame-to-move) uuid/zero))
    (t/is (= (:parent-id frame-to-move') (:id frame-parent')))))

(t/deftest test-relocate-shape-out-of-group
  (let [;; ==== Setup
        file                   (-> (thf/sample-file :file1)
                                   (tho/add-frame :frame-1)
                                   (tho/add-group :group-1 :parent-label :frame-1)
                                   (ths/add-sample-shape :circle-1 :parent-label :group-1))

        page                   (thf/current-page file)
        circle                 (ths/get-shape file :circle-1)
        group                  (ths/get-shape file :group-1)

        ;; ==== Action

        changes                (cls/generate-relocate (-> (pcb/empty-changes nil)
                                                          (pcb/with-page-id (:id page))
                                                          (pcb/with-objects (:objects page)))
                                                      uuid/zero             ;; parent-id
                                                      0                     ;; to-index
                                                      #{(:id circle)})      ;; ids

        file'                  (thf/apply-changes file changes)

        ;; ==== Get
        circle'                (ths/get-shape file' :circle-1)
        group'                 (ths/get-shape file' :group-1)]

    ;; ==== Check

    ;; the circle has moved, and the group is deleted
    (t/is (= (:parent-id circle) (:id group)))
    (t/is (= (:parent-id circle') uuid/zero))
    (t/is group)
    (t/is (nil? group'))))

(t/deftest test-relocate-shape-out-of-layout-manual
  (let [;; ==== Setup
        file (-> (thf/sample-file :file1)
                 (tho/add-frame :frame-1
                                :layout                 :flex     ;; TODO: those values come from main.data.workspace.shape_layout/default-layout-params
                                :layout-flex-dir        :row      ;;       it should be good to use it directly, but first it should be moved to common.logic
                                :layout-gap-type        :multiple
                                :layout-gap             {:row-gap 0 :column-gap 0}
                                :layout-align-items     :start
                                :layout-justify-content :start
                                :layout-align-content   :stretch
                                :layout-wrap-type       :nowrap
                                :layout-padding-type    :simple
                                :layout-padding         {:p1 0 :p2 0 :p3 0 :p4 0})
                 (ths/add-sample-shape :circle-1 :parent-label :frame-1
                                       :layout-item-margin      {:m1 10 :m2 10 :m3 10 :m4 10}
                                       :layout-item-margin-type :multiple
                                       :layout-item-h-sizing    :auto
                                       :layout-item-v-sizing    :auto
                                       :layout-item-max-h       1000
                                       :layout-item-min-h       100
                                       :layout-item-max-w       2000
                                       :layout-item-min-w       200
                                       :layout-item-absolute    false
                                       :layout-item-z-index     10))

        page   (thf/current-page file)
        circle (ths/get-shape file :circle-1)

        ;; ==== Action

        changes (cls/generate-relocate (-> (pcb/empty-changes nil)
                                           (pcb/with-page-id (:id page))
                                           (pcb/with-objects (:objects page)))
                                       uuid/zero             ;; parent-id
                                       0                     ;; to-index
                                       #{(:id circle)})      ;; ids

        ;; ==== Get
        file'   (thf/apply-changes file changes)
        circle' (ths/get-shape file' :circle-1)]

    ;; ==== Check

    ;; the layout item attributes are removed
    (t/is (nil? (:layout-item-margin circle')))
    (t/is (nil? (:layout-item-margin-type circle')))
    (t/is (nil? (:layout-item-h-sizing circle')))
    (t/is (nil? (:layout-item-v-sizing circle')))
    (t/is (nil? (:layout-item-max-h circle')))
    (t/is (nil? (:layout-item-min-h circle')))
    (t/is (nil? (:layout-item-max-w circle')))
    (t/is (nil? (:layout-item-min-w circle')))
    (t/is (nil? (:layout-item-absolute circle')))
    (t/is (nil? (:layout-item-z-index circle')))))

(t/deftest test-relocate-shape-out-of-layout-with-tokens
  (let [;; ==== Setup
        file (-> (tht/sample-file-with-tokens
                  :lib-fn #(-> %
                               (ctob/add-set (ctob/make-token-set :id (thi/new-id! :test-token-set)
                                                                  :name "test-token-set"))
                               (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :test-theme)
                                                                      :name "test-theme"
                                                                      :sets #{"test-token-set"}))
                               (ctob/add-token (thi/id :test-token-set)
                                               (ctob/make-token :id (thi/new-id! :token-sizing)
                                                                :name "token-sizing"
                                                                :type :sizing
                                                                :value 10))
                               (ctob/add-token (thi/id :test-token-set)
                                               (ctob/make-token :id (thi/new-id! :token-spacing)
                                                                :name "token-spacing"
                                                                :type :spacing
                                                                :value 30)))
                  :status-fn #(-> %
                                  (ctos/set-tokens-status #{(thi/id :test-theme)}
                                                          #{(thi/id :test-token-set)})))
                 ;; Sync tokens-status from lib's internal active-themes
                 (tho/add-frame :frame-1
                                :layout                 :flex     ;; TODO: those values come from main.data.workspace.shape_layout/default-layout-params
                                :layout-flex-dir        :row      ;;       it should be good to use it directly, but first it should be moved to common.logic
                                :layout-gap-type        :multiple
                                :layout-gap             {:row-gap 0 :column-gap 0}
                                :layout-align-items     :start
                                :layout-justify-content :start
                                :layout-align-content   :stretch
                                :layout-wrap-type       :nowrap
                                :layout-padding-type    :simple
                                :layout-padding         {:p1 0 :p2 0 :p3 0 :p4 0})
                 (ths/add-sample-shape :circle-1 :parent-label :frame-1)
                 (tht/apply-token-to-shape :circle-1
                                           "token-sizing"
                                           [:layout-item-max-h :layout-item-max-w :layout-item-min-h :layout-item-min-w]
                                           [:layout-item-max-h :layout-item-max-w :layout-item-min-h :layout-item-min-w]
                                           10)
                 (tht/apply-token-to-shape :circle-1
                                           "token-spacing"
                                           [:m1 :m2 :m3 :m4]
                                           [:layout-item-margin]
                                           {:m1 30 :m2 30 :m3 30 :m4 30}))

        page   (thf/current-page file)
        circle (ths/get-shape file :circle-1)

        ;; ==== Action

        changes (cls/generate-relocate (-> (pcb/empty-changes nil)
                                           (pcb/with-page-id (:id page))
                                           (pcb/with-objects (:objects page)))
                                       uuid/zero             ;; parent-id
                                       0                     ;; to-index
                                       #{(:id circle)})      ;; ids


        ;; ==== Get
        file'   (thf/apply-changes file changes)
        circle' (ths/get-shape file' :circle-1)]

    ;; ==== Check

    ;; the layout item attributes and tokens are removed
    (t/is (empty? (:applied-tokens circle')))
    (t/is (nil? (:layout-item-margin circle')))
    (t/is (nil? (:layout-item-margin-type circle')))
    (t/is (nil? (:layout-item-h-sizing circle')))
    (t/is (nil? (:layout-item-v-sizing circle')))
    (t/is (nil? (:layout-item-max-h circle')))
    (t/is (nil? (:layout-item-min-h circle')))
    (t/is (nil? (:layout-item-max-w circle')))
    (t/is (nil? (:layout-item-min-w circle')))
    (t/is (nil? (:layout-item-absolute circle')))
    (t/is (nil? (:layout-item-z-index circle')))))
(defn- add-row-board
  [file label & {:as attrs}]
  (tho/add-frame file label
                 (merge {:layout                 :flex
                         :layout-flex-dir        :row
                         :layout-gap-type        :multiple
                         :layout-gap             {:row-gap 0 :column-gap 0}
                         :layout-align-items     :start
                         :layout-justify-content :start
                         :layout-align-content   :stretch
                         :layout-wrap-type       :nowrap
                         :layout-padding-type    :simple
                         :layout-padding         {:p1 0 :p2 0 :p3 0 :p4 0}}
                        attrs)))

(defn- relocate
  [file parent-id to-index ids]
  (let [page    (thf/current-page file)
        changes (cls/generate-relocate (-> (pcb/empty-changes nil)
                                           (pcb/with-page-id (:id page))
                                           (pcb/with-objects (:objects page)))
                                       parent-id
                                       to-index
                                       ids)]
    (thf/apply-changes file changes)))

(t/deftest test-relocate-fill-shape-into-hug-board-fixes-the-shape
  (let [;; ==== Setup
        file  (-> (thf/sample-file :file1)
                  (add-row-board :hug
                                 :width 32 :height 40
                                 :layout-item-h-sizing :auto
                                 :layout-item-v-sizing :fix)
                  (ths/add-sample-shape :inside :parent-label :hug
                                        :width 32 :height 40
                                        :layout-item-h-sizing :fix
                                        :layout-item-v-sizing :fix)
                  (ths/add-sample-shape :arriving
                                        :width 32 :height 16
                                        :layout-item-h-sizing :fill
                                        :layout-item-v-sizing :fix))

        ;; ==== Action
        file' (relocate file (thi/id :hug) 0 #{(thi/id :arriving)})

        ;; ==== Check
        arriving' (ths/get-shape file' :arriving)
        hug'      (ths/get-shape file' :hug)]

    (t/is (= (thi/id :hug) (:parent-id arriving')))
    (t/is (= :fix (:layout-item-h-sizing arriving')))
    (t/is (= 32 (:width arriving')))
    (t/is (= :auto (:layout-item-h-sizing hug')))))

(t/deftest test-relocate-fill-shape-into-fixed-board-keeps-the-fill
  (let [file  (-> (thf/sample-file :file1)
                  (add-row-board :fixed
                                 :width 100 :height 40
                                 :layout-item-h-sizing :fix
                                 :layout-item-v-sizing :fix)
                  (ths/add-sample-shape :arriving
                                        :width 32 :height 16
                                        :layout-item-h-sizing :fill
                                        :layout-item-v-sizing :fix))
        file' (relocate file (thi/id :fixed) 0 #{(thi/id :arriving)})]

    (t/is (= :fill (:layout-item-h-sizing (ths/get-shape file' :arriving))))
    (t/is (= :fix (:layout-item-h-sizing (ths/get-shape file' :fixed))))))

(t/deftest test-relocate-last-sizing-child-out-of-hug-board-fixes-the-board
  (let [file  (-> (thf/sample-file :file1)
                  (add-row-board :hug
                                 :width 64 :height 40
                                 :layout-item-h-sizing :auto
                                 :layout-item-v-sizing :fix)
                  (ths/add-sample-shape :leaving :parent-label :hug
                                        :width 32 :height 40
                                        :layout-item-h-sizing :fix
                                        :layout-item-v-sizing :fix)
                  (ths/add-sample-shape :staying :parent-label :hug
                                        :width 32 :height 40
                                        :layout-item-h-sizing :fill
                                        :layout-item-v-sizing :fix))
        file' (relocate file uuid/zero 0 #{(thi/id :leaving)})]

    (t/is (= :fix (:layout-item-h-sizing (ths/get-shape file' :hug))))
    (t/is (= :fill (:layout-item-h-sizing (ths/get-shape file' :staying))))))

(t/deftest test-relocate-last-sizing-child-out-of-hug-grid-fixes-the-board
  (let [file  (-> (thf/sample-file :file1)
                  (tho/add-frame :hug
                                 :x 0 :y 0 :width 64 :height 40
                                 :layout :grid
                                 :layout-grid-dir :row
                                 :layout-grid-columns [{:type :flex :value 1}
                                                       {:type :flex :value 1}]
                                 :layout-grid-rows [{:type :flex :value 1}]
                                 :layout-grid-cells {}
                                 :layout-item-h-sizing :auto
                                 :layout-item-v-sizing :fix)
                  (ths/add-sample-shape :leaving :parent-label :hug
                                        :width 32 :height 40
                                        :layout-item-h-sizing :fix
                                        :layout-item-v-sizing :fix)
                  (ths/add-sample-shape :staying :parent-label :hug
                                        :width 32 :height 40
                                        :layout-item-h-sizing :fill
                                        :layout-item-v-sizing :fix))
        file' (relocate file uuid/zero 0 #{(thi/id :leaving)})]
    (t/is (= :fix (:layout-item-h-sizing (ths/get-shape file' :hug))))
    (t/is (= :fill (:layout-item-h-sizing (ths/get-shape file' :staying))))))

(t/deftest test-relocate-inside-the-same-hug-board-keeps-the-sizing
  (let [file  (-> (thf/sample-file :file1)
                  (add-row-board :hug
                                 :width 64 :height 40
                                 :layout-item-h-sizing :auto
                                 :layout-item-v-sizing :fix)
                  (ths/add-sample-shape :first :parent-label :hug
                                        :width 32 :height 40
                                        :layout-item-h-sizing :fix
                                        :layout-item-v-sizing :fix)
                  (ths/add-sample-shape :second :parent-label :hug
                                        :width 32 :height 40
                                        :layout-item-h-sizing :fix
                                        :layout-item-v-sizing :fix))
        file' (relocate file (thi/id :hug) 2 #{(thi/id :first)})]

    (t/is (= :auto (:layout-item-h-sizing (ths/get-shape file' :hug))))
    (t/is (= :fix (:layout-item-h-sizing (ths/get-shape file' :first))))))

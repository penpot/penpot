;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.logic.comp-remove-swap-slots-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.files.libraries-helpers :as cflh]
   [app.common.types.component :as ctk]
   [app.common.uuid :as uuid]
   [clojure.test :as t]
   [common-tests.helpers.compositions :as tho]
   [common-tests.helpers.files :as thf]
   [common-tests.helpers.ids-map :as thi]))

(t/use-fixtures :each thi/test-fixture)


;; Related .penpot file: common/test/cases/remove-swap-slots.penpot
(defn- setup-file
  []
    ;;  :frame-b1 [:id: 3aee2370-44e4-81c8-8004-46e56a459d70, :touched: ]
    ;;      :blue1 [:id: 3aee2370-44e4-81c8-8004-46e56a45fc55, :touched: #{:swap-slot-3aee2370-44e4-81c8-8004-46e56a459d75}]
    ;;      :green-copy [:id: 3aee2370-44e4-81c8-8004-46e56a45fc56, :touched: ]
    ;;          :blue-copy-in-green-copy [:id: 3aee2370-44e4-81c8-8004-46e56a4631a4, :touched: #{:swap-slot-3aee2370-44e4-81c8-8004-46e56a459d6f}]
    ;;      :frame-yellow [:id: 3aee2370-44e4-81c8-8004-46e56a459d73, :touched: ]
    ;;  :frame-green [:id: 3aee2370-44e4-81c8-8004-46e56a459d6c, :touched: ]
    ;;      :red-copy-green [:id: 3aee2370-44e4-81c8-8004-46e56a459d6f, :touched: ]
    ;;  :frame-blue [:id: 3aee2370-44e4-81c8-8004-46e56a459d69, :touched: ]
    ;;  :frame-b2 [:id: 3aee2370-44e4-81c8-8004-46e56a4631a5, :touched: ]
    ;;  :frame-red [:id: 3aee2370-44e4-81c8-8004-46e56a459d66, :touched: ]

  (-> (thf/sample-file :file1)
      (tho/add-frame :frame-red)
      (thf/make-component :red :frame-red)
      (tho/add-frame :frame-blue)
      (thf/make-component :blue :frame-blue)
      (tho/add-frame :frame-green)
      (thf/make-component :green :frame-green)
      (thf/instantiate-component :red :red-copy-green :parent-label :frame-green)
      (tho/add-frame :frame-b1)
      (thf/make-component :b1 :frame-b1)
      (tho/add-frame :frame-yellow :parent-label :frame-b1)
      (thf/instantiate-component :red :red-copy :parent-label :frame-b1)
      (thf/component-swap :red-copy :blue :blue1)
      (thf/instantiate-component :green :green-copy :parent-label :frame-b1 :children-labels [:red-copy-in-green-copy])
      (thf/component-swap :red-copy-in-green-copy :blue :blue-copy-in-green-copy)
      (tho/add-frame :frame-b2)
      (thf/make-component :b2 :frame-b2)))

(t/deftest test-keep-swap-slot-relocating-blue1-to-root
  (let [;; ============================== Setup ===============================
        file                   (setup-file)
        page                   (thf/current-page file)
        blue1                  (thf/get-shape file :blue1)

        ;; ============================== Action ==============================
        changes                (cflh/generate-relocate-shapes (pcb/empty-changes nil)
                                                              (:objects page)
                                                              #{(:parent-id blue1)} ;; parents
                                                              uuid/zero             ;; paremt-id
                                                              (:id page)            ;; page-id
                                                              0                     ;; to-index
                                                              #{(:id blue1)})       ;; ids
        file'                  (thf/apply-changes file changes)

        ;; ============================== Get =================================
        blue1'                 (thf/get-shape file' :blue1)]

    ;; ================================== Check ===============================
    ;; blue1 had swap-id before move
    (t/is (some? (ctk/get-swap-slot blue1)))

    ;; blue1 has not swap-id after move
    (t/is (some? blue1'))
    (t/is (nil? (ctk/get-swap-slot blue1')))))


(t/deftest test-keep-swap-slot-relocating-blue1-to-b2
  (let [;; ============================== Setup ===============================
        file                   (setup-file)
        page                   (thf/current-page file)
        blue1                  (thf/get-shape file :blue1)
        b2                     (thf/get-shape file :frame-b2)


        ;; ============================== Action ==============================
        changes                (cflh/generate-relocate-shapes (pcb/empty-changes nil)
                                                              (:objects page)
                                                              #{(:parent-id blue1)} ;; parents
                                                              (:id b2)              ;; parent-id
                                                              (:id page)            ;; page-id
                                                              0                     ;; to-index
                                                              #{(:id blue1)})       ;; ids
        file'                  (thf/apply-changes file changes)

        ;; ============================== Get =================================
        blue1'                 (thf/get-shape file' :blue1)]

    ;; ================================== Check ===============================
    ;; blue1 had swap-id before move
    (t/is (some? (ctk/get-swap-slot blue1)))

    ;; blue1 has not swap-id after move
    (t/is (some? blue1'))
    (t/is (nil? (ctk/get-swap-slot blue1')))))

(t/deftest test-keep-swap-slot-relocating-yellow-to-root
  (let [;; ============================== Setup ===============================
        file                   (setup-file)
        page                   (thf/current-page file)
        blue1                  (thf/get-shape file :blue1)
        yellow                 (thf/get-shape file :frame-yellow)

        ;; ============================== Action ==============================
        ;; Move blue1 into yellow
        changes                (cflh/generate-relocate-shapes (pcb/empty-changes nil)
                                                              (:objects page)
                                                              #{(:parent-id blue1)} ;; parents
                                                              (:id yellow)          ;; parent-id
                                                              (:id page)            ;; page-id
                                                              0                     ;; to-index
                                                              #{(:id blue1)})       ;; ids

        file'                  (thf/apply-changes file changes)
        page'                  (thf/current-page file')
        yellow'                (thf/get-shape file' :frame-yellow)

        ;; Move yellow into root
        changes'               (cflh/generate-relocate-shapes (pcb/empty-changes nil)
                                                              (:objects page')
                                                              #{(:parent-id yellow')} ;; parents
                                                              uuid/zero               ;; parent-id
                                                              (:id page')             ;; page-id
                                                              0                       ;; to-index
                                                              #{(:id yellow')})       ;; ids
        file''                  (thf/apply-changes file' changes')

        ;; ============================== Get =================================
        blue1''                 (thf/get-shape file'' :blue1)]

    ;; ================================== Check ===============================
    ;; blue1 had swap-id before move
    (t/is (some? (ctk/get-swap-slot blue1)))

    ;; blue1 has not swap-id after move
    (t/is (some? blue1''))
    (t/is (nil? (ctk/get-swap-slot blue1'')))))


(t/deftest test-keep-swap-slot-relocating-yellow-to-b2
  (let [;; ============================== Setup ===============================
        file                   (setup-file)
        page                   (thf/current-page file)
        blue1                  (thf/get-shape file :blue1)
        yellow                 (thf/get-shape file :frame-yellow)

        ;; ============================== Action ==============================
        ;; Move blue1 into yellow
        changes                (cflh/generate-relocate-shapes (pcb/empty-changes nil)
                                                              (:objects page)
                                                              #{(:parent-id blue1)} ;; parents
                                                              (:id yellow)          ;; parent-id
                                                              (:id page)            ;; page-id
                                                              0                     ;; to-index
                                                              #{(:id blue1)})       ;; ids

        file'                  (thf/apply-changes file changes)
        page'                  (thf/current-page file')
        yellow'                (thf/get-shape file' :frame-yellow)
        b2'                    (thf/get-shape file' :frame-b2)

        ;; Move yellow into b2
        changes'               (cflh/generate-relocate-shapes (pcb/empty-changes nil)
                                                              (:objects page')
                                                              #{(:parent-id yellow')} ;; parents
                                                              (:id b2')               ;; parent-id
                                                              (:id page')             ;; page-id
                                                              0                       ;; to-index
                                                              #{(:id yellow')})       ;; ids
        file''                  (thf/apply-changes file' changes')

        ;; ============================== Get =================================
        blue1''                 (thf/get-shape file'' :blue1)]

    ;; ================================== Check ===============================
    ;; blue1 had swap-id before move
    (t/is (some? (ctk/get-swap-slot blue1)))

    ;; blue1 has not swap-id after move
    (t/is (some? blue1''))
    (t/is (nil? (ctk/get-swap-slot blue1'')))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.logic.comp-remove-swap-slots-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.point :as gpt]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.component :as ctk]
   [app.common.uuid :as uuid]
   [clojure.test :as t]
   [cuerdas.core :as str]))

(t/use-fixtures :each thi/test-fixture)

;; Related .penpot file: common/test/cases/remove-swap-slots.penpot
(defn- setup-file
  []
  ;; {:frame-red} [:name Frame1]                           # [Component :red]
  ;; {:frame-blue} [:name Frame1]                          # [Component :blue]
  ;; {:frame-green} [:name Frame1]                         # [Component :green]
  ;;     :red-copy-green [:name Frame1]                    @--> :frame-red
  ;; {:frame-b1} [:name Frame1]                            # [Component :b1]
  ;;     :blue1 [:name Frame1, :swap-slot-label :red-copy] @--> :frame-blue
  ;;     :frame-yellow [:name Frame1]
  ;;     :green-copy [:name Frame1]                        @--> :frame-green
  ;;         :blue-copy-in-green-copy [:name Frame1, :swap-slot-label :red-copy-green] @--> :frame-blue
  ;; {:frame-b2} [:name Frame1]                            # [Component :b2]
  (-> (thf/sample-file :file1)
      (tho/add-frame :frame-red)
      (thc/make-component :red :frame-red)
      (tho/add-frame :frame-blue :name "frame-blue")
      (thc/make-component :blue :frame-blue)
      (tho/add-frame :frame-green)
      (thc/make-component :green :frame-green)
      (thc/instantiate-component :red :red-copy-green :parent-label :frame-green)
      (tho/add-frame :frame-b1)
      (thc/make-component :b1 :frame-b1)
      (tho/add-frame :frame-yellow :parent-label :frame-b1 :name "frame-yellow")
      (thc/instantiate-component :red :red-copy :parent-label :frame-b1)
      (thc/component-swap :red-copy :blue :blue1)
      (thc/instantiate-component :green :green-copy :parent-label :frame-b1 :children-labels [:red-copy-in-green-copy])
      (thc/component-swap :red-copy-in-green-copy :blue :blue-copy-in-green-copy)
      (tho/add-frame :frame-b2)
      (thc/make-component :b2 :frame-b2)))

(t/deftest test-remove-swap-slot-relocating-blue1-to-root
  (let [;; ==== Setup
        file                   (setup-file)

        page                   (thf/current-page file)
        blue1                  (ths/get-shape file :blue1)

        ;; ==== Action
        changes                (cls/generate-relocate-shapes (pcb/empty-changes nil)
                                                             (:objects page)
                                                             #{(:parent-id blue1)} ;; parents
                                                             uuid/zero             ;; parent-id
                                                             (:id page)            ;; page-id
                                                             0                     ;; to-index
                                                             #{(:id blue1)})       ;; ids
        file'                  (thf/apply-changes file changes)

        ;; ==== Get
        blue1'                 (ths/get-shape file' :blue1)]

    ;; ==== Check

    ;; blue1 had swap-id before move
    (t/is (some? (ctk/get-swap-slot blue1)))

    ;; blue1 has not swap-id after move
    (t/is (some? blue1'))
    (t/is (nil? (ctk/get-swap-slot blue1')))))

(t/deftest test-remove-swap-slot-move-blue1-to-root
  (let [;; ==== Setup
        file                   (setup-file)
        page                   (thf/current-page file)
        blue1                  (ths/get-shape file :blue1)

        ;; ==== Action
        changes                (cls/generate-move-shapes-to-frame (pcb/empty-changes nil)
                                                                  #{(:id blue1)}       ;; ids
                                                                  uuid/zero            ;; frame-id
                                                                  (:id page)           ;; page-id
                                                                  (:objects page)      ;; objects
                                                                  0                    ;; drop-index
                                                                  nil)                 ;; cell

        file'                  (thf/apply-changes file changes)

        ;; ==== Get
        blue1'                 (ths/get-shape file' :blue1)]

    ;; ==== Check

    ;; blue1 had swap-id before move
    (t/is (some? (ctk/get-swap-slot blue1)))

    ;; blue1 has not swap-id after move
    (t/is (some? blue1'))
    (t/is (nil? (ctk/get-swap-slot blue1')))))


(t/deftest test-remove-swap-slot-relocating-blue1-to-b2
  (let [;; ==== Setup
        file                   (setup-file)
        page                   (thf/current-page file)
        blue1                  (ths/get-shape file :blue1)
        b2                     (ths/get-shape file :frame-b2)


        ;; ==== Action
        changes                (cls/generate-relocate-shapes (pcb/empty-changes nil)
                                                             (:objects page)
                                                             #{(:parent-id blue1)} ;; parents
                                                             (:id b2)              ;; parent-id
                                                             (:id page)            ;; page-id
                                                             0                     ;; to-index
                                                             #{(:id blue1)})       ;; ids
        file'                  (thf/apply-changes file changes)

        ;; ==== Get
        blue1'                 (ths/get-shape file' :blue1)]

    ;; ==== Check

    ;; blue1 had swap-id before move
    (t/is (some? (ctk/get-swap-slot blue1)))

    ;; blue1 has not swap-id after move
    (t/is (some? blue1'))
    (t/is (nil? (ctk/get-swap-slot blue1')))))

(t/deftest test-remove-swap-slot-move-blue1-to-b2
  (let [;; ==== Setup
        file                   (setup-file)
        page                   (thf/current-page file)
        blue1                  (ths/get-shape file :blue1)
        b2                     (ths/get-shape file :frame-b2)


        ;; ==== Action
        changes                (cls/generate-move-shapes-to-frame (pcb/empty-changes nil)
                                                                  #{(:id blue1)}       ;; ids
                                                                  (:id b2)             ;; frame-id
                                                                  (:id page)           ;; page-id
                                                                  (:objects page)      ;; objects
                                                                  0                    ;; drop-index
                                                                  nil)                 ;; cell

        file'                  (thf/apply-changes file changes)

        ;; ==== Get
        blue1'                 (ths/get-shape file' :blue1)]

    ;; ==== Check

    ;; blue1 had swap-id before move
    (t/is (some? (ctk/get-swap-slot blue1)))

    ;; blue1 has not swap-id after move
    (t/is (some? blue1'))
    (t/is (nil? (ctk/get-swap-slot blue1')))))

(t/deftest test-remove-swap-slot-relocating-yellow-to-root
  (let [;; ==== Setup
        file                   (setup-file)
        page                   (thf/current-page file)
        blue1                  (ths/get-shape file :blue1)
        yellow                 (ths/get-shape file :frame-yellow)

        ;; ==== Action
        ;; Move blue1 into yellow
        changes                (cls/generate-relocate-shapes (pcb/empty-changes nil)
                                                             (:objects page)
                                                             #{(:parent-id blue1)} ;; parents
                                                             (:id yellow)          ;; parent-id
                                                             (:id page)            ;; page-id
                                                             0                     ;; to-index
                                                             #{(:id blue1)})       ;; ids

        file'                  (thf/apply-changes file changes)
        page'                  (thf/current-page file')
        yellow'                (ths/get-shape file' :frame-yellow)

        ;; Move yellow into root
        changes'               (cls/generate-relocate-shapes (pcb/empty-changes nil)
                                                             (:objects page')
                                                             #{(:parent-id yellow')} ;; parents
                                                             uuid/zero               ;; parent-id
                                                             (:id page')             ;; page-id
                                                             0                       ;; to-index
                                                             #{(:id yellow')})       ;; ids
        file''                  (thf/apply-changes file' changes')

        ;; ==== Get
        blue1''                 (ths/get-shape file'' :blue1)]

    ;; ==== Check

    ;; blue1 had swap-id before move
    (t/is (some? (ctk/get-swap-slot blue1)))

    ;; blue1 has not swap-id after move
    (t/is (some? blue1''))
    (t/is (nil? (ctk/get-swap-slot blue1'')))))

(t/deftest test-remove-swap-slot-move-yellow-to-root
  (let [;; ==== Setup
        file                   (setup-file)
        page                   (thf/current-page file)
        blue1                  (ths/get-shape file :blue1)
        yellow                 (ths/get-shape file :frame-yellow)

        ;; ==== Action
        ;; Move blue1 into yellow
        changes                (cls/generate-move-shapes-to-frame (pcb/empty-changes nil)
                                                                  #{(:id blue1)}       ;; ids
                                                                  (:id yellow)         ;; frame-id
                                                                  (:id page)           ;; page-id
                                                                  (:objects page)      ;; objects
                                                                  0                    ;; drop-index
                                                                  nil)                 ;; cell

        file'                  (thf/apply-changes file changes)
        page'                  (thf/current-page file')
        yellow'                (ths/get-shape file' :frame-yellow)

        ;; Move yellow into root
        changes'               (cls/generate-move-shapes-to-frame (pcb/empty-changes nil)
                                                                  #{(:id yellow')}      ;; ids
                                                                  uuid/zero            ;; frame-id
                                                                  (:id page')           ;; page-id
                                                                  (:objects page')      ;; objects
                                                                  0                    ;; drop-index
                                                                  nil)                 ;; cell
        file''                  (thf/apply-changes file' changes')

        ;; ==== Get
        blue1''                 (ths/get-shape file'' :blue1)]

    ;; ==== Check

    ;; blue1 had swap-id before move
    (t/is (some? (ctk/get-swap-slot blue1)))

    ;; blue1 has not swap-id after move
    (t/is (some? blue1''))
    (t/is (nil? (ctk/get-swap-slot blue1'')))))


(t/deftest test-remove-swap-slot-relocating-yellow-to-b2
  (let [;; ==== Setup
        file                   (setup-file)
        page                   (thf/current-page file)
        blue1                  (ths/get-shape file :blue1)
        yellow                 (ths/get-shape file :frame-yellow)

        ;; ==== Action
        ;; Move blue1 into yellow
        changes                (cls/generate-relocate-shapes (pcb/empty-changes nil)
                                                             (:objects page)
                                                             #{(:parent-id blue1)} ;; parents
                                                             (:id yellow)          ;; parent-id
                                                             (:id page)            ;; page-id
                                                             0                     ;; to-index
                                                             #{(:id blue1)})       ;; ids

        file'                  (thf/apply-changes file changes)
        page'                  (thf/current-page file')
        yellow'                (ths/get-shape file' :frame-yellow)
        b2'                    (ths/get-shape file' :frame-b2)

        ;; Move yellow into b2
        changes'               (cls/generate-relocate-shapes (pcb/empty-changes nil)
                                                             (:objects page')
                                                             #{(:parent-id yellow')} ;; parents
                                                             (:id b2')               ;; parent-id
                                                             (:id page')             ;; page-id
                                                             0                       ;; to-index
                                                             #{(:id yellow')})       ;; ids
        file''                  (thf/apply-changes file' changes')

        ;; ==== Get
        blue1''                 (ths/get-shape file'' :blue1)]

    ;; ==== Check

    ;; blue1 had swap-id before move
    (t/is (some? (ctk/get-swap-slot blue1)))

    ;; blue1 has not swap-id after move
    (t/is (some? blue1''))
    (t/is (nil? (ctk/get-swap-slot blue1'')))))

(t/deftest test-remove-swap-slot-move-yellow-to-b2
  (let [;; ==== Setup
        file                   (setup-file)
        page                   (thf/current-page file)
        blue1                  (ths/get-shape file :blue1)
        yellow                 (ths/get-shape file :frame-yellow)

        ;; ==== Action
        ;; Move blue1 into yellow
        changes                (cls/generate-move-shapes-to-frame (pcb/empty-changes nil)
                                                                  #{(:id blue1)}       ;; ids
                                                                  (:id yellow)         ;; frame-id
                                                                  (:id page)           ;; page-id
                                                                  (:objects page)      ;; objects
                                                                  0                    ;; drop-index
                                                                  nil)                 ;; cell

        file'                  (thf/apply-changes file changes)
        page'                  (thf/current-page file')
        yellow'                (ths/get-shape file' :frame-yellow)
        b2'                    (ths/get-shape file' :frame-b2)

        ;; Move yellow into b2
        changes'                (cls/generate-move-shapes-to-frame (pcb/empty-changes nil)
                                                                   #{(:id yellow')}   ;; ids
                                                                   (:id b2')          ;; frame-id
                                                                   (:id page')        ;; page-id
                                                                   (:objects page')   ;; objects
                                                                   0                  ;; drop-index
                                                                   nil)               ;; cell

        file''                  (thf/apply-changes file' changes')

        ;; ==== Get
        blue1''                 (ths/get-shape file'' :blue1)]

    ;; ==== Check

    ;; blue1 had swap-id before move
    (t/is (some? (ctk/get-swap-slot blue1)))

    ;; blue1 has not swap-id after move
    (t/is (some? blue1''))
    (t/is (nil? (ctk/get-swap-slot blue1'')))))

(defn- find-duplicated-shape
  [original-shape page]
  ;; duplicated shape has the same name, the same parent, and doesn't have a label
  (->> (vals (:objects page))
       (filter #(and (= (:name %) (:name original-shape))
                     (= (:parent-id %) (:parent-id original-shape))
                     (str/starts-with? (thi/label (:id %)) "<no-label")))
       first))

(t/deftest test-remove-swap-slot-duplicating-blue1
  (let [;; ==== Setup
        file                   (setup-file)

        page                   (thf/current-page file)
        blue1                  (ths/get-shape file :blue1)

        ;; ==== Action
        changes                (-> (pcb/empty-changes nil)
                                   (cll/generate-duplicate-changes (:objects page)         ;; objects
                                                                   page                    ;; page
                                                                   #{(:id blue1)}          ;; ids
                                                                   (gpt/point 0 0)         ;; delta
                                                                   {(:id  file) file}      ;; libraries
                                                                   (:data file)            ;; library-data
                                                                   (:id file))             ;; file-id
                                   (cll/generate-duplicate-changes-update-indices (:objects page)  ;; objects
                                                                                  #{(:id blue1)})) ;; ids



        file'                  (thf/apply-changes file changes)

        ;; ==== Get
        page'                  (thf/current-page file')
        blue1'                 (ths/get-shape file' :blue1)
        duplicated-blue1'      (find-duplicated-shape blue1' page')]

    ;; ==== Check

    ;; blue1 has swap-id
    (t/is (some? (ctk/get-swap-slot blue1')))

    ;; duplicated-blue1 has not swap-id
    (t/is (some? duplicated-blue1'))
    (t/is (nil? (ctk/get-swap-slot duplicated-blue1')))))

(t/deftest test-remove-swap-slot-duplicate-yellow
  (let [;; ==== Setup
        file                   (setup-file)
        page                   (thf/current-page file)
        blue1                  (ths/get-shape file :blue1)
        yellow                 (ths/get-shape file :frame-yellow)

        ;; ==== Action
        ;; Move blue1 into yellow
        changes                (cls/generate-move-shapes-to-frame (pcb/empty-changes nil)
                                                                  #{(:id blue1)}       ;; ids
                                                                  (:id yellow)         ;; frame-id
                                                                  (:id page)           ;; page-id
                                                                  (:objects page)      ;; objects
                                                                  0                    ;; drop-index
                                                                  nil)                 ;; cell

        file'                  (thf/apply-changes file changes)
        page'                  (thf/current-page file')
        yellow'                (ths/get-shape file' :frame-yellow)

        ;; Duplicate yellow
        changes'               (-> (pcb/empty-changes nil)
                                   (cll/generate-duplicate-changes (:objects page')          ;; objects
                                                                   page'                     ;; page
                                                                   #{(:id yellow')}          ;; ids
                                                                   (gpt/point 0 0)           ;; delta
                                                                   {(:id  file') file'}      ;; libraries
                                                                   (:data file')             ;; library-data
                                                                   (:id file'))              ;; file-id
                                   (cll/generate-duplicate-changes-update-indices (:objects page')   ;; objects
                                                                                  #{(:id yellow')})) ;; ids

        file''                  (thf/apply-changes file' changes')

        ;; ==== Get
        page''                  (thf/current-page file'')
        blue1''                 (ths/get-shape file'' :blue1)
        yellow''                (ths/get-shape file'' :frame-yellow)


        duplicated-yellow''     (find-duplicated-shape yellow'' page'')
        duplicated-blue1-id''   (-> duplicated-yellow''
                                    :shapes
                                    first)
        duplicated-blue1''      (get (:objects page'') duplicated-blue1-id'')]

    ;; ==== Check

    ;; blue1'' has swap-id
    (t/is (some? (ctk/get-swap-slot blue1'')))

    ;; duplicated-blue1'' has not swap-id
    (t/is (some? duplicated-blue1''))
    (t/is (nil? (ctk/get-swap-slot duplicated-blue1'')))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.comp-move-swapped-copies-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.point :as gpt]
   [app.common.logic.duplicate :as cld]
   [app.common.logic.instances :as cli]
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
  ;;     :blue1 [:name Frame1]                             @--> :frame-blue
  ;;     :frame-yellow [:name Frame1]
  ;;     :green-copy [:name Frame1]                        @--> :frame-green
  ;;         :blue2 [:name Frame1] (swapped)               @--> :frame-blue
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
      (thc/component-swap :red-copy-in-green-copy :blue :blue2)
      (tho/add-frame :frame-b2)
      (thc/make-component :b2 :frame-b2)))

(t/deftest test-relocating-blue1-to-root
  (let [;; ==== Setup
        file                   (setup-file)

        page                   (thf/current-page file)
        blue1                  (ths/get-shape file :blue1)

        ;; ==== Action
        changes                (cls/generate-relocate (-> (pcb/empty-changes nil)
                                                          (pcb/with-page-id (:id page))
                                                          (pcb/with-objects (:objects page)))
                                                      uuid/zero             ;; parent-id
                                                      0                     ;; to-index
                                                      #{(:id blue1)})       ;; ids

        file'                  (thf/apply-changes file changes)

        ;; ==== Get
        blue1'                 (ths/get-shape file' :blue1)]

    ;; ==== Check

    ;; blue1 was swapped in a main, so it is not marked
    (t/is (not (ctk/swapped? blue1)))

    ;; blue1 is still a plain nested copy
    (t/is (some? blue1'))
    (t/is (not (ctk/swapped? blue1')))))

(t/deftest test-relocating-blue1-to-b2
  (let [;; ==== Setup
        file                   (setup-file)
        page                   (thf/current-page file)
        blue1                  (ths/get-shape file :blue1)
        b2                     (ths/get-shape file :frame-b2)


        ;; ==== Action
        changes                (cls/generate-relocate (-> (pcb/empty-changes nil)
                                                          (pcb/with-page-id (:id page))
                                                          (pcb/with-objects (:objects page)))
                                                      (:id b2)              ;; parent-id
                                                      0                     ;; to-index
                                                      #{(:id blue1)})       ;; ids

        file'                  (thf/apply-changes file changes)

        ;; ==== Get
        blue1'                 (ths/get-shape file' :blue1)]

    ;; ==== Check

    ;; blue1 was swapped in a main, so it is not marked
    (t/is (not (ctk/swapped? blue1)))

    ;; blue1 is still a plain nested copy
    (t/is (some? blue1'))
    (t/is (not (ctk/swapped? blue1')))))

(t/deftest test-relocating-yellow-to-root
  (let [;; ==== Setup
        file                   (setup-file)
        page                   (thf/current-page file)
        blue1                  (ths/get-shape file :blue1)
        yellow                 (ths/get-shape file :frame-yellow)

        ;; ==== Action
        ;; Move blue1 into yellow
        changes                (cls/generate-relocate (-> (pcb/empty-changes nil)
                                                          (pcb/with-page-id (:id page))
                                                          (pcb/with-objects (:objects page)))
                                                      (:id yellow)          ;; parent-id
                                                      0                     ;; to-index
                                                      #{(:id blue1)})       ;; ids


        file'                  (thf/apply-changes file changes)
        page'                  (thf/current-page file')
        yellow'                (ths/get-shape file' :frame-yellow)

        ;; Move yellow into root
        changes'               (cls/generate-relocate (-> (pcb/empty-changes nil)
                                                          (pcb/with-page-id (:id page'))
                                                          (pcb/with-objects (:objects page')))
                                                      uuid/zero               ;; parent-id
                                                      0                       ;; to-index
                                                      #{(:id yellow')})       ;; ids

        file''                  (thf/apply-changes file' changes')

        ;; ==== Get
        blue1''                 (ths/get-shape file'' :blue1)]

    ;; ==== Check

    ;; blue1 was swapped in a main, so it is not marked
    (t/is (not (ctk/swapped? blue1)))

    ;; blue1 is still a plain nested copy
    (t/is (some? blue1''))
    (t/is (not (ctk/swapped? blue1'')))))

(t/deftest test-relocating-yellow-to-b2
  (let [;; ==== Setup
        file                   (setup-file)
        page                   (thf/current-page file)
        blue1                  (ths/get-shape file :blue1)
        yellow                 (ths/get-shape file :frame-yellow)

        ;; ==== Action
        ;; Move blue1 into yellow
        changes                (cls/generate-relocate (-> (pcb/empty-changes nil)
                                                          (pcb/with-page-id (:id page))
                                                          (pcb/with-objects (:objects page)))
                                                      (:id yellow)          ;; parent-id
                                                      0                     ;; to-index
                                                      #{(:id blue1)})       ;; ids


        file'                  (thf/apply-changes file changes)
        page'                  (thf/current-page file')
        yellow'                (ths/get-shape file' :frame-yellow)
        b2'                    (ths/get-shape file' :frame-b2)

        ;; Move yellow into b2
        changes'               (cls/generate-relocate (-> (pcb/empty-changes nil)
                                                          (pcb/with-page-id (:id page'))
                                                          (pcb/with-objects (:objects page')))
                                                      (:id b2')               ;; parent-id
                                                      0                       ;; to-index
                                                      #{(:id yellow')})       ;; ids

        file''                  (thf/apply-changes file' changes')

        ;; ==== Get
        blue1''                 (ths/get-shape file'' :blue1)]

    ;; ==== Check

    ;; blue1 was swapped in a main, so it is not marked
    (t/is (not (ctk/swapped? blue1)))

    ;; blue1 is still a plain nested copy
    (t/is (some? blue1''))
    (t/is (not (ctk/swapped? blue1'')))))

(defn- find-duplicated-shape
  [original-shape page]
  ;; duplicated shape has the same name, the same parent, and doesn't have a label
  (->> (vals (:objects page))
       (filter #(and (= (:name %) (:name original-shape))
                     (= (:parent-id %) (:parent-id original-shape))
                     (str/starts-with? (thi/label (:id %)) "<no-label")))
       first))

(t/deftest test-duplicating-blue1
  (let [;; ==== Setup
        file                   (setup-file)

        page                   (thf/current-page file)
        blue1                  (ths/get-shape file :blue1)

        ;; ==== Action
        changes                (-> (pcb/empty-changes nil)
                                   (cld/generate-duplicate-changes (:objects page)         ;; objects
                                                                   page                    ;; page
                                                                   #{(:id blue1)}          ;; ids
                                                                   (gpt/point 0 0)         ;; delta
                                                                   {(:id  file) file}      ;; libraries
                                                                   (:data file)            ;; library-data
                                                                   (:id file))             ;; file-id
                                   (cld/generate-duplicate-changes-update-indices (:objects page)  ;; objects
                                                                                  #{(:id blue1)})) ;; ids



        file'                  (thf/apply-changes file changes)

        ;; ==== Get
        page'                  (thf/current-page file')
        blue1'                 (ths/get-shape file' :blue1)
        duplicated-blue1'      (find-duplicated-shape blue1' page')]

    ;; ==== Check

    ;; blue1 was swapped in a main, so it is not marked
    (t/is (not (ctk/swapped? blue1')))

    ;; neither is its duplicate
    (t/is (some? duplicated-blue1'))
    (t/is (not (ctk/swapped? duplicated-blue1')))))

(t/deftest test-duplicate-yellow
  (let [;; ==== Setup
        file                   (setup-file)
        page                   (thf/current-page file)
        blue1                  (ths/get-shape file :blue1)
        yellow                 (ths/get-shape file :frame-yellow)

        ;; ==== Action
        ;; Move blue1 into yellow
        changes                (cls/generate-relocate (-> (pcb/empty-changes nil)
                                                          (pcb/with-page-id (:id page))
                                                          (pcb/with-objects (:objects page)))
                                                      (:id yellow)          ;; parent-id
                                                      0                     ;; to-index
                                                      #{(:id blue1)})       ;; ids

        file'                  (thf/apply-changes file changes)
        page'                  (thf/current-page file')
        yellow'                (ths/get-shape file' :frame-yellow)

        ;; Duplicate yellow
        changes'               (-> (pcb/empty-changes nil)
                                   (cld/generate-duplicate-changes (:objects page')          ;; objects
                                                                   page'                     ;; page
                                                                   #{(:id yellow')}          ;; ids
                                                                   (gpt/point 0 0)           ;; delta
                                                                   {(:id  file') file'}      ;; libraries
                                                                   (:data file')             ;; library-data
                                                                   (:id file'))              ;; file-id
                                   (cld/generate-duplicate-changes-update-indices (:objects page')   ;; objects
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

    ;; blue1 was swapped in a main, so it is not marked
    (t/is (not (ctk/swapped? blue1'')))

    ;; neither is its duplicate
    (t/is (some? duplicated-blue1''))
    (t/is (not (ctk/swapped? duplicated-blue1'')))))

(t/deftest test-relocating-blue1-before-yellow
  (let [;; ==== Setup
        file                   (setup-file)

        page                   (thf/current-page file)
        blue1                  (ths/get-shape file :blue1)

        ;; ==== Action
        changes                (cls/generate-relocate (-> (pcb/empty-changes nil)
                                                          (pcb/with-page-id (:id page))
                                                          (pcb/with-objects (:objects page)))
                                                      (:parent-id blue1)    ;; parent-id
                                                      2                     ;; to-index
                                                      #{(:id blue1)})       ;; ids

        file'                  (thf/apply-changes file changes)

        ;; ==== Get
        blue1'                 (ths/get-shape file' :blue1)]

    ;; ==== Check

    ;; blue1 was swapped in a main, so it is not marked
    (t/is (not (ctk/swapped? blue1)))

    ;; blue1 is still a plain nested copy
    (t/is (some? blue1'))
    (t/is (not (ctk/swapped? blue1')))))

(t/deftest test-move-blue1-inside-and-outside-yellow
  (let [;; ==== Setup
        file                   (setup-file)
        page                   (thf/current-page file)
        blue1                  (ths/get-shape file :blue1)
        yellow                 (ths/get-shape file :frame-yellow)

        ;; ==== Action
        ;; Move blue1 into yellow
        changes                (cls/generate-relocate (-> (pcb/empty-changes nil)
                                                          (pcb/with-page-id (:id page))
                                                          (pcb/with-objects (:objects page)))
                                                      (:id yellow)          ;; parent-id
                                                      0                     ;; to-index
                                                      #{(:id blue1)})       ;; ids

        file'                  (thf/apply-changes file changes)

        ;; Move blue1 outside yellow
        page'                  (thf/current-page file')
        blue1'                 (ths/get-shape file' :blue1)
        b1'                    (ths/get-shape file' :frame-b1)
        changes'               (cls/generate-relocate (-> (pcb/empty-changes nil)
                                                          (pcb/with-page-id (:id page'))
                                                          (pcb/with-objects (:objects page')))
                                                      (:id b1')             ;; parent-id
                                                      0                     ;; to-index
                                                      #{(:id blue1')})      ;; ids

        file''                  (thf/apply-changes file' changes')

        ;; ==== Get
        blue1''                 (ths/get-shape file'' :blue1)]

    ;; ==== Check

    ;; blue1 was swapped in a main, so it is not marked
    (t/is (not (ctk/swapped? blue1)))

    ;; blue1 is still a plain nested copy
    (t/is (some? blue1''))
    (t/is (not (ctk/swapped? blue1'')))))


(t/deftest test-relocate-blue1-inside-and-outside-yellow
  (let [;; ==== Setup
        file                   (setup-file)
        page                   (thf/current-page file)
        blue1                  (ths/get-shape file :blue1)
        yellow                 (ths/get-shape file :frame-yellow)

        ;; ==== Action
        ;; Relocate blue1 into yellow
        changes                (cls/generate-relocate (-> (pcb/empty-changes nil)
                                                          (pcb/with-page-id (:id page))
                                                          (pcb/with-objects (:objects page)))
                                                      (:id yellow)          ;; parent-id
                                                      0                     ;; to-index
                                                      #{(:id blue1)})       ;; ids


        file'                  (thf/apply-changes file changes)

        ;; Relocate blue1 outside yellow
        page'                  (thf/current-page file')
        blue1'                 (ths/get-shape file' :blue1)
        b1'                    (ths/get-shape file' :frame-b1)
        changes'               (cls/generate-relocate (-> (pcb/empty-changes nil)
                                                          (pcb/with-page-id (:id page'))
                                                          (pcb/with-objects (:objects page')))
                                                      (:id b1')          ;; parent-id
                                                      0                  ;; to-index
                                                      #{(:id blue1')})   ;; ids


        file''                  (thf/apply-changes file' changes')

        ;; ==== Get
        blue1''                 (ths/get-shape file'' :blue1)]

    ;; ==== Check

    ;; blue1 was swapped in a main, so it is not marked
    (t/is (not (ctk/swapped? blue1)))

    ;; blue1 is still a plain nested copy
    (t/is (some? blue1''))
    (t/is (not (ctk/swapped? blue1'')))))


(t/deftest test-relocating-green-copy-to-root
  (let [;; ==== Setup
        file                   (setup-file)

        page                   (thf/current-page file)
        blue2                  (ths/get-shape file :blue2)
        green-copy             (ths/get-shape file :green-copy)

        ;; ==== Action
        changes                (cls/generate-relocate (-> (pcb/empty-changes nil)
                                                          (pcb/with-page-id (:id page))
                                                          (pcb/with-objects (:objects page)))
                                                      uuid/zero                  ;; parent-id
                                                      0                          ;; to-index
                                                      #{(:id green-copy)})       ;; ids

        file'                  (thf/apply-changes file changes)

        ;; ==== Get
        blue2'                 (ths/get-shape file' :blue2)]

    ;; ==== Check

    ;; blue2 is swapped before the move
    (t/is (ctk/swapped? blue2))

    ;; blue2 is still swapped after the move
    (t/is (some? blue2'))
    (t/is (ctk/swapped? blue2'))))

(t/deftest test-relocating-green-copy-to-b2
  (let [;; ==== Setup
        file                   (setup-file)

        page                   (thf/current-page file)
        blue2                  (ths/get-shape file :blue2)
        green-copy             (ths/get-shape file :green-copy)
        b2                     (ths/get-shape file :frame-b2)

        ;; ==== Action
        changes                (cls/generate-relocate (-> (pcb/empty-changes nil)
                                                          (pcb/with-page-id (:id page))
                                                          (pcb/with-objects (:objects page)))
                                                      (:id b2)                   ;; parent-id
                                                      0                          ;; to-index
                                                      #{(:id green-copy)})       ;; ids

        file'                  (thf/apply-changes file changes)

        ;; ==== Get
        blue2'                 (ths/get-shape file' :blue2)]

    ;; ==== Check

    ;; blue2 is swapped before the move
    (t/is (ctk/swapped? blue2))

    ;; blue2 is still swapped after the move
    (t/is (some? blue2'))
    (t/is (ctk/swapped? blue2'))))

(t/deftest test-duplicating-green-copy
  (let [;; ==== Setup
        file                   (setup-file)

        page                   (thf/current-page file)
        green-copy             (ths/get-shape file :green-copy)

        ;; ==== Action
        changes                (-> (pcb/empty-changes nil)
                                   (cld/generate-duplicate-changes (:objects page)         ;; objects
                                                                   page                    ;; page
                                                                   #{(:id green-copy)}     ;; ids
                                                                   (gpt/point 0 0)         ;; delta
                                                                   {(:id  file) file}      ;; libraries
                                                                   (:data file)            ;; library-data
                                                                   (:id file))             ;; file-id
                                   (cld/generate-duplicate-changes-update-indices (:objects page)       ;; objects
                                                                                  #{(:id green-copy)})) ;; ids



        file'                  (thf/apply-changes file changes)

        ;; ==== Get
        page'                  (thf/current-page file')
        blue1'                 (ths/get-shape file' :blue1)
        green-copy'            (ths/get-shape file :green-copy)
        duplicated-green-copy' (find-duplicated-shape green-copy' page')
        duplicated-blue1-id'   (-> duplicated-green-copy'
                                   :shapes
                                   first)
        duplicated-blue1'      (get (:objects page') duplicated-blue1-id')]

    ;; ==== Check

    ;; blue1 was swapped in a main, so it is a plain nested copy
    (t/is (not (ctk/swapped? blue1')))

    ;; the duplicate of blue2 is swapped too
    (t/is (some? duplicated-blue1'))
    (t/is (ctk/swapped? duplicated-blue1'))))

(t/deftest test-swap-outside-component-is-not-marked
  (let [;; ==== Setup
        file                   (setup-file)
        ;; ==== Action

        file'                  (-> file
                                   (thc/instantiate-component :red :red-copy1)
                                   (thc/component-swap :red-copy1 :blue :blue-copy1))

        ;; ==== Get
        blue-copy1'            (ths/get-shape file' :blue-copy1)]

    ;; ==== Check

    ;; blue-copy1 is not swapped
    (t/is (some? blue-copy1'))
    (t/is (not (ctk/swapped? blue-copy1')))))

(t/deftest test-detach-ends-the-swap
  (let [;; ==== Setup
        file                   (setup-file)

        page                   (thf/current-page file)
        green-copy             (ths/get-shape file :green-copy)
        blue2                  (ths/get-shape file :blue2)

        ;; ==== Action
        changes                (cli/generate-detach-component (pcb/empty-changes)
                                                              (:id green-copy)
                                                              (:data file)
                                                              (:id page)
                                                              {(:id file) file})
        file'                  (thf/apply-changes file changes)

        ;; ==== Get
        blue2'                 (ths/get-shape file' :blue2)]

    ;; ==== Check

    ;; blue2 is swapped before the detach
    (t/is (ctk/swapped? blue2))

    ;; blue2 is a plain copy after the detach
    (t/is (some? blue2'))
    (t/is (not (ctk/swapped? blue2')))))

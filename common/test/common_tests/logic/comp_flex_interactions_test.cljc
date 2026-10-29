;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.comp-flex-interactions-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.libraries :as cll]
   [app.common.math :as mth]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.modifiers :as ctm]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- setup-file
  "Flex component and copy whose children navigate to different boards."
  []
  (-> (thf/sample-file :file1)
      (tho/add-simple-component-with-copy :component1
                                          :main-root
                                          :main-child
                                          :copy-root
                                          :main-root-params {:layout :flex
                                                             :layout-flex-dir :row}
                                          :copy-root-params {:children-labels [:copy-child]})
      (ths/add-sample-shape :main-popup :type :frame)
      (ths/add-sample-shape :copy-popup :type :frame)
      (ths/add-interaction :main-child :main-popup)
      (ths/add-interaction :copy-child :copy-popup)))

(defn- destinations
  [shape]
  (mapv :destination (:interactions shape)))

(t/deftest test-sync-flex-main-keeps-copy-interactions
  (let [;; ==== Setup
        file         (setup-file)
        margin       {:m1 5 :m2 5 :m3 5 :m4 5}

        ;; ==== Action
        updated-file (tho/change-shape file :main-child
                                       #(assoc % :hidden true :layout-item-margin margin))
        changes      (cll/generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     (:id updated-file)
                                                     (thi/id :component1)
                                                     (:id updated-file)
                                                     {(:id updated-file) updated-file}
                                                     (:id updated-file))
        file'        (thf/apply-changes updated-file changes)

        ;; ==== Get
        copy-child'  (ths/get-shape file' :copy-child)]

    ;; ==== Check
    (t/is (true? (:hidden copy-child')))
    (t/is (= margin (:layout-item-margin copy-child')))
    (t/is (= [(thi/id :copy-popup)] (destinations copy-child')))))

(t/deftest test-sync-flex-copy-to-main-keeps-main-interactions
  (let [;; ==== Setup
        file         (setup-file)

        ;; ==== Action
        updated-file (tho/change-shape file :copy-child #(assoc % :hidden true))
        page         (thf/current-page updated-file)
        container    (ctn/make-container page :page)
        changes      (-> (pcb/empty-changes)
                         (pcb/with-container container)
                         (cll/generate-sync-shape-inverse (:data updated-file)
                                                          {(:id updated-file) updated-file}
                                                          container
                                                          (thi/id :copy-root)))
        file'        (thf/apply-changes updated-file changes)

        ;; ==== Get
        main-child'  (ths/get-shape file' :main-child)]

    ;; ==== Check
    (t/is (true? (:hidden main-child')))
    (t/is (= [(thi/id :main-popup)] (destinations main-child')))))

(defn- setup-library-and-file
  "Library flex component with a nested copy, and a file instance whose
  nested copy has an interaction."
  []
  (let [library (-> (thf/sample-file :library)
                    (tho/add-nested-component :component1
                                              :main1-root
                                              :main1-child
                                              :component2
                                              :main2-root
                                              :nested-head
                                              :main2-root-params {:layout :flex
                                                                  :layout-flex-dir :row}))
        file    (-> (thf/sample-file :file)
                    (ths/add-sample-shape :copy-popup :type :frame)
                    (thc/instantiate-component :component2
                                               :copy2-root
                                               :library library
                                               :children-labels [:copy-nested-head])
                    (ths/add-interaction :copy-nested-head :copy-popup))]
    [library file]))

(defn- sync-file-from-library
  [file library]
  (cll/generate-sync-file-changes (pcb/empty-changes)
                                  nil
                                  nil
                                  (:id file)
                                  nil
                                  (:id library)
                                  {(:id library) library
                                   (:id file) file}
                                  (:id file)))

(t/deftest test-sync-library-flex-keeps-nested-copy-interactions
  (let [;; ==== Setup
        [library file]    (setup-library-and-file)
        copy-x            (:x (tho/bottom-shape file :copy2-root))

        ;; ==== Action
        library'          (-> library
                              (tho/change-shape :main1-child #(gsh/move % (gpt/point 1 0)))
                              (tho/propagate-component-changes :component1))
        file'             (thf/apply-changes file (sync-file-from-library file library'))

        ;; ==== Get
        copy-nested-head' (ths/get-shape file' :copy-nested-head)]

    ;; ==== Check
    (t/is (mth/close? (inc copy-x) (:x (tho/bottom-shape file' :copy2-root))))
    (t/is (= [(thi/id :copy-popup)] (destinations copy-nested-head')))))

(defn- swap-to-library-component
  "Swap a shape in `file` to a component that lives in `library`, keeping
  flex-item attrs the way production `component-swap` does."
  [file library shape-label new-component-label]
  (let [shape             (ths/get-shape file shape-label)
        page              (thf/current-page file)
        keep-props-values (select-keys shape ctk/swap-keep-attrs)
        libraries         {(:id library) library
                           (:id file) file}
        id-new            (:id (thc/get-component library new-component-label))
        [_ _ changes]     (cll/generate-component-swap
                           (pcb/empty-changes nil (:id page))
                           (:objects page)
                           shape
                           (:data library)
                           page
                           libraries
                           id-new
                           0
                           nil
                           keep-props-values
                           false)]
    (thf/apply-changes file changes)))

(defn- setup-library-with-absolute-nested-icon
  "Library: icon A, icon B, and a flex board whose nested child is an
  instance of icon A with absolute position."
  []
  (-> (thf/sample-file :library)
      (tho/add-simple-component :icon-a :icon-a-root :icon-a-child)
      (tho/add-simple-component :icon-b :icon-b-root :icon-b-child)
      (tho/add-frame :board-root {:name "Board"
                                  :layout :flex
                                  :layout-flex-dir :row})
      (thc/instantiate-component :icon-a :nested-head {:parent-label :board-root})
      (tho/change-shape :nested-head #(assoc % :layout-item-absolute true))
      (thc/make-component :board :board-root)))

(t/deftest test-sync-library-keeps-swapped-nested-absolute
  (let [;; ==== Setup
        library      (setup-library-with-absolute-nested-icon)
        file         (-> (thf/sample-file :file)
                         (thc/instantiate-component :board
                                                    :copy-board
                                                    :library library
                                                    :children-labels [:copy-nested-head]))
        file         (swap-to-library-component file library :copy-nested-head :icon-b)
        swapped      (ths/get-shape file :copy-nested-head)

        ;; ==== Action
        library'     (tho/change-shape library :board-root
                                       #(assoc % :fills (ths/sample-fills-color :fill-color "#fabada")))
        file'        (thf/apply-changes file (sync-file-from-library file library'))
        swapped'     (ths/get-shape file' :copy-nested-head)]

    ;; ==== Check
    (t/is (true? (:layout-item-absolute swapped)))
    (t/is (some? (ctk/get-swap-slot swapped)))
    (t/is (= (thi/id :icon-b) (:component-id swapped)))
    (t/is (some? (ctk/get-swap-slot swapped')))
    (t/is (= (thi/id :icon-b) (:component-id swapped')))
    (t/is (true? (:layout-item-absolute swapped')))))

(t/deftest test-sync-library-propagates-slot-absolute-to-swapped-nested
  (let [;; ==== Setup
        library  (setup-library-with-absolute-nested-icon)
        file     (-> (thf/sample-file :file)
                     (thc/instantiate-component :board
                                                :copy-board
                                                :library library
                                                :children-labels [:copy-nested-head]))
        file     (swap-to-library-component file library :copy-nested-head :icon-b)

        ;; ==== Action
        library' (tho/change-shape library :nested-head
                                   #(assoc % :layout-item-absolute false))
        file'    (thf/apply-changes file (sync-file-from-library file library'))
        swapped' (ths/get-shape file' :copy-nested-head)]

    ;; ==== Check
    (t/is (some? (ctk/get-swap-slot swapped')))
    (t/is (= (thi/id :icon-b) (:component-id swapped')))
    (t/is (not (true? (:layout-item-absolute swapped'))))))

(t/deftest test-sync-library-flex-child-attrs-to-unswapped-sibling
  (let [;; ==== Setup
        margin   {:m1 5 :m2 5 :m3 5 :m4 5}
        library  (-> (thf/sample-file :library)
                     (tho/add-simple-component :icon-a :icon-a-root :icon-a-child)
                     (tho/add-simple-component :icon-b :icon-b-root :icon-b-child)
                     (tho/add-frame :board-root {:name "Board"
                                                 :layout :flex
                                                 :layout-flex-dir :row})
                     (thc/instantiate-component :icon-a :nested-head {:parent-label :board-root})
                     (ths/add-sample-shape :sibling :type :rect :name "Sibling" :parent-label :board-root)
                     (tho/change-shape :nested-head #(assoc % :layout-item-absolute true))
                     (thc/make-component :board :board-root))
        file     (-> (thf/sample-file :file)
                     (thc/instantiate-component :board
                                                :copy-board
                                                :library library
                                                :children-labels [:copy-nested-head :copy-sibling]))
        file     (swap-to-library-component file library :copy-nested-head :icon-b)

        ;; ==== Action
        library' (tho/change-shape library :sibling
                                   #(assoc % :layout-item-margin margin))
        file'    (thf/apply-changes file (sync-file-from-library file library'))
        sibling' (ths/get-shape file' :copy-sibling)
        swapped' (ths/get-shape file' :copy-nested-head)]

    ;; ==== Check
    (t/is (= margin (:layout-item-margin sibling')))
    (t/is (true? (:layout-item-absolute swapped')))
    (t/is (some? (ctk/get-swap-slot swapped')))))

(defn- setup-library-with-icon-slot
  "Library: icon A, icon B, and a flex board whose flex `:slot` frame holds an
  absolute instance of icon A."
  []
  (-> (thf/sample-file :library)
      (tho/add-simple-component :icon-a :icon-a-root :icon-a-child)
      (tho/add-simple-component :icon-b :icon-b-root :icon-b-child)
      (tho/add-frame :board-root {:name "Board"
                                  :layout :flex
                                  :layout-flex-dir :row})
      (tho/add-frame :slot {:name "Slot"
                            :parent-label :board-root
                            :layout :flex
                            :layout-flex-dir :row})
      (thc/instantiate-component :icon-a :nested-head {:parent-label :slot})
      (tho/change-shape :nested-head #(assoc % :layout-item-absolute true))
      (thc/make-component :board :board-root)))

(defn- instantiate-board
  "File with a copy of the library board, labelling its slot and nested icon."
  [library]
  (let [file (-> (thf/sample-file :file)
                 (thc/instantiate-component :board
                                            :copy-board
                                            :library library
                                            :children-labels [:copy-slot]))
        slot (ths/get-shape file :copy-slot)]
    (thi/set-id! :copy-nested-head (first (:shapes slot)))
    file))

(defn- offset-in-slot
  [file slot-label icon-label]
  (let [slot (ths/get-shape file slot-label)
        icon (ths/get-shape file icon-label)]
    (gpt/point (- (:x icon) (:x slot))
               (- (:y icon) (:y slot)))))

(defn- sync-after-unrelated-change
  [file library root-label]
  (let [library' (tho/change-shape library root-label
                                   #(assoc % :fills (ths/sample-fills-color :fill-color "#fabada")))]
    (thf/apply-changes file (sync-file-from-library file library'))))

(t/deftest test-sync-places-swapped-child-in-moved-slot
  (let [;; ==== Setup
        library (setup-library-with-icon-slot)
        file    (-> (instantiate-board library)
                    (swap-to-library-component library :copy-nested-head :icon-b)
                    ;; The layout moves the slot, and a previous sync moved the icon away
                    (tho/move-shape-tree :copy-slot (gpt/point 800 0) :ignore-touched true)
                    (tho/move-shape-tree :copy-nested-head (gpt/point 800 0) :ignore-touched true))

        ;; ==== Action
        file'   (sync-after-unrelated-change file library :board-root)]

    ;; ==== Check
    (t/is (gpt/close? (offset-in-slot library :slot :nested-head)
                      (offset-in-slot file' :copy-slot :copy-nested-head)))
    (t/is (true? (:layout-item-absolute (ths/get-shape file' :copy-nested-head))))))

(t/deftest test-sync-keeps-moved-child-in-moved-slot
  (let [;; ==== Setup
        library (setup-library-with-icon-slot)
        file    (-> (instantiate-board library)
                    (tho/move-shape-tree :copy-nested-head (gpt/point 5 0))
                    (tho/move-shape-tree :copy-slot (gpt/point 800 0) :ignore-touched true))
        before  (offset-in-slot file :copy-slot :copy-nested-head)

        ;; ==== Action
        file'   (sync-after-unrelated-change file library :board-root)]

    ;; ==== Check
    (t/is (gpt/close? before (offset-in-slot file' :copy-slot :copy-nested-head)))))

(t/deftest test-sync-keeps-user-moved-swapped-child-in-moved-slot
  (let [;; ==== Setup
        library (setup-library-with-icon-slot)
        file    (-> (instantiate-board library)
                    (swap-to-library-component library :copy-nested-head :icon-b)
                    (tho/move-shape-tree :copy-nested-head (gpt/point 5 0))
                    (tho/move-shape-tree :copy-slot (gpt/point 800 0) :ignore-touched true))
        before  (offset-in-slot file :copy-slot :copy-nested-head)

        ;; ==== Action
        file'   (sync-after-unrelated-change file library :board-root)]

    ;; ==== Check
    (t/is (gpt/close? before (offset-in-slot file' :copy-slot :copy-nested-head)))))

(defn- with-shapes-first
  "Puts the shapes with the given labels first in the page objects, in that
  order. The sync visits the shapes of a page in this order."
  [file labels]
  (let [page-id (:id (thf/current-page file))
        ids     (mapv thi/id labels)]
    (update-in file [:data :pages-index page-id :objects]
               (fn [objects]
                 (apply array-map (concat (mapcat #(vector % (get objects %)) ids)
                                          (mapcat identity (apply dissoc objects ids))))))))

(t/deftest test-sync-places-swapped-child-where-main-slot-occupant-is
  (let [;; ==== Setup
        library  (setup-library-with-icon-slot)
        file     (-> (instantiate-board library)
                     (swap-to-library-component library :copy-nested-head :icon-b)
                     ;; The copy comes before its swapped icon, which the sync also syncs on its own
                     (with-shapes-first [:copy-board :copy-nested-head]))

        ;; ==== Action
        library' (-> library
                     (tho/move-shape-tree :nested-head (gpt/point 4 4))
                     (tho/change-shape :icon-b-root #(gsh/transform-shape % (ctm/change-size % 32 nil))))
        file'    (thf/apply-changes file (sync-file-from-library file library'))

        ;; ==== Get
        icon'    (ths/get-shape file' :copy-nested-head)]

    ;; ==== Check
    (t/is (gpt/close? (offset-in-slot library' :slot :nested-head)
                      (offset-in-slot file' :copy-slot :copy-nested-head)))
    (t/is (mth/close? (:width (ths/get-shape library' :icon-b-root)) (:width icon')))
    (t/is (mth/close? (:x icon') (-> icon' :selrect :x)))))

(defn- setup-library-with-root-icon
  "Library: icon A, and a 300px wide flex card whose `:close` child, placed
  directly in the root, is an absolute instance of icon A with the given
  horizontal constraint."
  [constraints-h]
  (-> (thf/sample-file :library)
      (tho/add-simple-component :icon-a :icon-a-root :icon-a-child
                                :root-params {:width 24 :height 24})
      (tho/add-simple-component :icon-b :icon-b-root :icon-b-child
                                :root-params {:width 24 :height 24})
      (tho/add-frame :card-root {:name "Card"
                                 :layout :flex
                                 :layout-flex-dir :row
                                 :width 300
                                 :height 48})
      (thc/instantiate-component :icon-a :close {:parent-label :card-root})
      (tho/change-shape :close #(assoc % :layout-item-absolute true :constraints-h constraints-h))
      (thc/make-component :card :card-root)))

(defn- instantiate-filled-card
  "File with a copy of the card, widened by 800px and with its close icon
  moved by `icon-shift`, the way the layout does when the copy fills a wider
  parent."
  [library icon-shift]
  (-> (thf/sample-file :file)
      (thc/instantiate-component :card
                                 :copy-card
                                 :library library
                                 :children-labels [:copy-close])
      (tho/change-shape :copy-card
                        #(gsh/transform-shape % (ctm/change-size % (+ (:width %) 800) nil))
                        :ignore-touched true)
      (tho/move-shape-tree :copy-close (gpt/point icon-shift 0) :ignore-touched true)))

(t/deftest test-sync-moved-root-child-follows-root-through-right-constraint
  (let [;; ==== Setup
        library (setup-library-with-root-icon :right)
        file    (-> (instantiate-filled-card library 800)
                    (tho/move-shape-tree :copy-close (gpt/point 5 0)))

        ;; ==== Action
        file'   (sync-after-unrelated-change file library :card-root)]

    ;; ==== Check
    (t/is (gpt/close? (gpt/add (offset-in-slot library :card-root :close) (gpt/point 5 0))
                      (offset-in-slot file' :copy-card :copy-close)))))

(t/deftest test-sync-scales-swapped-root-child-back-with-root
  (let [;; ==== Setup
        library (setup-library-with-root-icon :scale)
        file    (-> (instantiate-filled-card library 0)
                    (swap-to-library-component library :copy-close :icon-b)
                    ;; The production swap keeps the constraints; the test swap does not
                    (tho/change-shape :copy-close #(assoc % :constraints-h :scale)))

        ;; ==== Action
        file'   (sync-after-unrelated-change file library :card-root)

        ;; ==== Get
        scale   (/ (:width (ths/get-shape file' :copy-card))
                   (:width (ths/get-shape file :copy-card)))]

    ;; ==== Check
    (t/is (mth/close? (* scale (:width (ths/get-shape file :copy-close)))
                      (:width (ths/get-shape file' :copy-close))))))

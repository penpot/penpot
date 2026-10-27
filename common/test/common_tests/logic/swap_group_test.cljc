;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.swap-group-test
  "A swapped copy sub-head stored as an override: it keeps the shape-ref of
  the main child it replaces, its component gives the content, and the
  touched group :swap-group marks the swap."
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.duplicate :as cld]
   [app.common.logic.instances :as cli]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.logic.swap :as clsw]
   [app.common.logic.variants :as clv]
   [app.common.math :as mth]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.comp.runner :as r]
   [app.common.test-helpers.composable.comp.setups :as setups]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.normalize :as thn]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.variants :as thv]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.modifiers :as ctm]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest swapping-a-grid-child-keeps-the-parent-cells-untouched
  (let [initial (setups/grid-component-with-copy)
        swapped (-> initial (r/step (n/swap-shape :copy-child1 :other)) r/settle)
        parent (tm/resolve-shape swapped :grid-copy)
        moved (-> swapped (r/step (n/move-to-cell :grid-root :child1 1 3)) r/settle)
        final-parent (tm/resolve-shape moved :grid-copy)
        child-id (:id (tm/resolve-shape moved :copy-child1))
        cell (first (filter #(some #{child-id} (:shapes %)) (vals (:layout-grid-cells final-parent))))]
    (t/is (nil? (:touched parent)))
    (t/is (= (:layout-grid-cells (tm/resolve-shape initial :grid-copy)) (:layout-grid-cells parent)))
    (t/is (= 3 (:column cell)))))

(defn- setup
  []
  ;; {:star-main}  [:name Frame1]          # [Component :star]
  ;;     :star-rect [:name Rect1]
  ;; {:heart-main} [:name Frame1]          # [Component :heart]
  ;;     :heart-rect [:name Rect1]
  ;; {:card-main}  [:name Frame1]          # [Component :card]
  ;;     :card-star [:name Frame1]         @--> [Component :star] :star-main
  ;; :copy [:name Frame1]                  #--> [Component :card] :card-main
  ;;     :copy-star [:name Frame1]         @--> [Component :heart] :card-star (swapped)
  (-> (thf/sample-file :file1)
      (tho/add-simple-component :star :star-main :star-rect
                                :child-params {:fills (ths/sample-fills-color :fill-color "#111111")})
      (tho/add-simple-component :heart :heart-main :heart-rect
                                :child-params {:fills (ths/sample-fills-color :fill-color "#222222")})
      (tho/add-frame :card-main)
      (thc/instantiate-component :star :card-star :parent-label :card-main)
      (thc/make-component :card :card-main)
      (thc/instantiate-component :card :copy :children-labels [:copy-star])
      (tho/swap-component-in-shape :copy-star :heart)))

(defn- propagate
  [file & component-labels]
  (reduce tho/propagate-component-changes file component-labels))

(defn- move-shape
  "Move the shape and its children by the given delta."
  [file label dx dy]
  (let [page    (thf/current-page file)
        ids     (set (cfh/get-children-ids-with-self (:objects page) (thi/id label)))
        changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                            ids
                                            #(gsh/move % (gpt/point dx dy))
                                            (:objects page)
                                            {})]
    (thf/apply-changes file changes)))

(defn- position
  [file label]
  (let [shape (ths/get-shape file label)]
    [(get-in shape [:selrect :x]) (get-in shape [:selrect :y])]))

(defn- bottom-position
  [file label]
  (let [shape (tho/bottom-shape file label)]
    [(get-in shape [:selrect :x]) (get-in shape [:selrect :y])]))

(t/deftest swapped-head-refers-to-the-replaced-child
  (let [file (setup)
        head (ths/get-shape file :copy-star)]
    (t/is (= (thi/id :card-star) (:shape-ref head)))
    (t/is (= (:id (thc/get-component file :heart)) (:component-id head)))
    (t/is (ctk/swapped? head))
    (t/is (= "#222222" (tho/bottom-fill-color file :copy)))))

(t/deftest swapped-head-follows-its-own-component
  (let [file (-> (setup)
                 (tho/update-color :heart-rect "#333333")
                 (propagate :heart)
                 (thf/validate-file!))]
    (t/is (= "#333333" (tho/bottom-fill-color file :copy)))))

(t/deftest swapped-head-ignores-the-replaced-component
  (let [file (-> (setup)
                 (tho/update-color :star-rect "#444444")
                 (propagate :star :card)
                 (thf/validate-file!))
        head (ths/get-shape file :copy-star)]
    (t/is (= "#444444" (tho/bottom-fill-color file :card-main)))
    (t/is (= "#222222" (tho/bottom-fill-color file :copy)))
    (t/is (= (:id (thc/get-component file :heart)) (:component-id head)))
    (t/is (ctk/swapped? head))))

(t/deftest swapped-head-gets-the-children-added-to-its-component
  (let [file (-> (setup)
                 (ths/add-sample-shape :heart-extra :parent-label :heart-main :type :rect)
                 (propagate :heart)
                 (thf/validate-file!))
        head (ths/get-shape file :copy-star)]
    (t/is (= 2 (count (:shapes head))))
    (t/is (some #(= (thi/id :heart-extra) (:shape-ref (ths/get-shape-by-id file %)))
                (:shapes head)))))

(t/deftest swapped-head-survives-a-sync-of-the-outer-copy
  (let [file (-> (setup)
                 (ths/update-shape :card-main :opacity 0.5)
                 (propagate :card)
                 (thf/validate-file!))
        head (ths/get-shape file :copy-star)]
    (t/is (= 0.5 (:opacity (ths/get-shape file :copy))))
    (t/is (= (:id (thc/get-component file :heart)) (:component-id head)))
    (t/is (= "#222222" (tho/bottom-fill-color file :copy)))))

(t/deftest swapped-head-follows-the-position-of-the-replaced-child
  (let [file  (setup)
        [x y] (position file :copy-star)
        [cx cy] (bottom-position file :copy)
        file' (-> file
                  (move-shape :card-star 10 20)
                  (propagate :card)
                  (thf/validate-file!))]
    (t/is (= [(+ x 10) (+ y 20)] (position file' :copy-star)))
    (t/is (= [(+ cx 10) (+ cy 20)] (bottom-position file' :copy)))
    (t/is (ctk/swapped? (ths/get-shape file' :copy-star)))))

(t/deftest swapped-head-follows-the-constraints-of-the-replaced-child
  (let [file (-> (setup)
                 (ths/update-shape :card-star :constraints-h :right)
                 (propagate :card)
                 (thf/validate-file!))]
    (t/is (= :right (:constraints-h (ths/get-shape file :copy-star))))))

(t/deftest swapped-head-content-sync-keeps-the-replaced-childs-placement
  (let [file      (-> (setup)
                      (ths/update-shape :card-star :constraints-h :right)
                      (ths/update-shape :card-star :constraints-v :bottom)
                      (ths/update-shape :card-star :fixed-scroll true)
                      (ths/update-shape :heart-main :constraints-h :scale)
                      (ths/update-shape :heart-main :constraints-v :scale)
                      (ths/update-shape :heart-main :fixed-scroll false)
                      (ths/update-shape :heart-main :opacity 0.4)
                      (propagate :card))
        placement #(select-keys (ths/get-shape % :copy-star)
                                [:constraints-h :constraints-v :fixed-scroll])
        expected  {:constraints-h :right :constraints-v :bottom :fixed-scroll true}
        synced    (-> file (propagate :heart) thf/validate-file!)
        reversed  (-> file (propagate :heart :card) thf/validate-file!)]
    (t/is (= expected (placement file)))
    (t/is (= expected (placement synced)))
    (t/is (= (placement synced) (placement reversed)))
    (t/is (= 0.4 (:opacity (ths/get-shape synced :copy-star))))
    (t/is (= #{:swap-group} (:touched (ths/get-shape synced :copy-star))))))

(t/deftest moved-swapped-head-keeps-its-position
  (let [file  (-> (setup)
                  (move-shape :copy-star 5 5))
        [x y] (position file :copy-star)
        file' (-> file
                  (move-shape :card-star 10 20)
                  (propagate :card)
                  (thf/validate-file!))]
    (t/is (ctk/touched-group? (ths/get-shape file :copy-star) :geometry-group))
    (t/is (= [x y] (position file' :copy-star)))))

(defn- set-opacity
  "Change the opacity of the shape as the user would, touching it."
  [file label opacity]
  (let [page    (thf/current-page file)
        changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                            #{(thi/id label)}
                                            #(assoc % :opacity opacity)
                                            (:objects page)
                                            {})]
    (thf/apply-changes file changes)))

(t/deftest reset-of-a-swapped-head-undoes-the-swap-in-place
  (let [file (-> (setup)
                 (set-opacity :copy 0.5)
                 (tho/reset-overrides-in-first-child :copy)
                 (thf/validate-file!))
        head (ths/get-shape file :copy-star)]
    (t/is (some? head))
    (t/is (= (:id (thc/get-component file :star)) (:component-id head)))
    (t/is (= (thi/id :card-star) (:shape-ref head)))
    (t/is (not (ctk/swapped? head)))
    (t/is (= "#111111" (tho/bottom-fill-color file :copy)))
    ;; nothing outside the reset head changes
    (t/is (= 0.5 (:opacity (ths/get-shape file :copy))))
    (t/is (ctk/touched-group? (ths/get-shape file :copy) :layer-effects-group))))

(t/deftest reset-of-the-outer-copy-undoes-the-swap-in-place
  (let [file (-> (setup)
                 (as-> file (tho/reset-overrides file (ths/get-shape file :copy)))
                 (thf/validate-file!))
        head (ths/get-shape file :copy-star)]
    (t/is (some? head))
    (t/is (= (:id (thc/get-component file :star)) (:component-id head)))
    (t/is (not (ctk/swapped? head)))
    (t/is (= "#111111" (tho/bottom-fill-color file :copy)))))

(t/deftest reset-in-a-main-keeps-the-overrides-of-its-copies
  ;; {:page-main}                      # [Component :page]
  ;;     :inner-copy                   @--> [Component :card]
  ;;         :inner-star               @--> [Component :heart] (swapped)
  ;; :outer-copy                       #--> [Component :page]
  ;;     :outer-inner                  @--> :inner-copy
  ;;         :outer-star               @--> :inner-star (opacity changed)
  (let [file (-> (setup)
                 (tho/add-frame :page-main)
                 (thc/instantiate-component :card :inner-copy
                                            :parent-label :page-main
                                            :children-labels [:inner-star])
                 (tho/swap-component-in-shape :inner-star :heart)
                 (thc/make-component :page :page-main)
                 (thc/instantiate-component :page :outer-copy
                                            :children-labels [:outer-inner :outer-star])
                 (set-opacity :outer-star 0.3)
                 (as-> file (tho/reset-overrides file (ths/get-shape file :inner-copy)
                                                 :propagate-fn #(propagate % :page))))
        star (ths/get-shape file :outer-star)]
    (t/is (= (:id (thc/get-component file :star))
             (:component-id (ths/get-shape file :inner-star))))
    (t/is (some? star))
    (t/is (= (:id (thc/get-component file :star)) (:component-id star)))
    (t/is (= 0.3 (:opacity star)))
    (t/is (= "#111111" (tho/bottom-fill-color file :outer-copy)))))

(defn- detach
  [file label]
  (let [page    (thf/current-page file)
        changes (cli/generate-detach-instance (-> (pcb/empty-changes nil)
                                                  (pcb/with-page page)
                                                  (pcb/with-objects (:objects page)))
                                              page
                                              {(:id file) file}
                                              (thi/id label))]
    (thf/apply-changes file changes)))

(defn- copy-objects
  "The objects the clipboard keeps when the shape is copied."
  [file label]
  (let [page    (thf/current-page file)
        objects (:objects page)
        ids     (cfh/get-children-ids-with-self objects (thi/id label))]
    (cld/advance-copied-objects file
                                page
                                {(:id file) file}
                                (select-keys objects ids)
                                [(thi/id label)])))

(defn- setup-two-levels
  []
  ;; {:dot-main}   [:name Frame1]          # [Component :dot]
  ;;     :dot-rect [:name Rect1]
  ;; {:badge-main} [:name Frame1]          # [Component :badge]
  ;;     :badge-dot [:name Frame1]         @--> [Component :dot] :dot-main
  ;; {:page-main}  [:name Frame1]          # [Component :page]
  ;;     :inner-copy [:name Frame1]        @--> [Component :card] :card-main
  ;;         :inner-star [:name Frame1]    @--> [Component :star] :card-star
  ;; :outer-copy [:name Frame1]            #--> [Component :page] :page-main
  ;;     :outer-inner [:name Frame1]       @--> [Component :card] :inner-copy
  ;;         :outer-star [:name Frame1]    @--> [Component :badge] :inner-star (swapped)
  ;;             <no-label>                @--> [Component :dot] :badge-dot
  (-> (setup)
      (tho/add-simple-component :dot :dot-main :dot-rect)
      (tho/add-frame :badge-main)
      (thc/instantiate-component :dot :badge-dot :parent-label :badge-main)
      (thc/make-component :badge :badge-main)
      (tho/add-frame :page-main)
      (thc/instantiate-component :card :inner-copy
                                 :parent-label :page-main
                                 :children-labels [:inner-star])
      (thc/make-component :page :page-main)
      (thc/instantiate-component :page :outer-copy
                                 :children-labels [:outer-inner :outer-star])
      (tho/swap-component-in-shape :outer-star :badge)))

(defn- first-child
  [file label]
  (ths/get-shape-by-id file (first (:shapes (ths/get-shape file label)))))

(t/deftest detach-makes-a-swapped-head-a-copy-of-its-component
  (let [file (-> (setup)
                 (detach :copy)
                 (thf/validate-file!))
        head (ths/get-shape file :copy-star)]
    (t/is (= (thi/id :heart-main) (:shape-ref head)))
    (t/is (not (ctk/swapped? head)))
    (t/is (ctk/instance-root? head))
    (t/is (= (thi/id :heart-rect) (:shape-ref (first-child file :copy-star))))
    (t/is (= "#333333" (-> file
                           (tho/update-color :heart-rect "#333333")
                           (propagate :heart)
                           (tho/bottom-fill-color :copy-star))))))

(t/deftest detach-keeps-a-swapped-heads-inherited-placement-as-overrides
  (let [initial  (-> (setup)
                     (ths/update-shape :card-star :constraints-h :right)
                     (ths/update-shape :card-star :fixed-scroll true)
                     (propagate :card))
        detached (detach initial :copy)
        synced   (-> detached (propagate :heart) thf/validate-file!)
        head     (ths/get-shape synced :copy-star)]
    (t/is (= :right (:constraints-h head)))
    (t/is (true? (:fixed-scroll head)))
    (t/is (= #{:constraints-group} (:touched (ths/get-shape detached :copy-star))))
    (t/is (= (:touched (ths/get-shape detached :copy-star)) (:touched head)))))

(t/deftest copied-swapped-head-keeps-its-placement-ownership
  (let [initial (-> (setup)
                    (ths/update-shape :card-star :fixed-scroll true)
                    (propagate :card))
        objects (copy-objects initial :copy-star)
        head    (get objects (thi/id :copy-star))]
    (t/is (true? (:fixed-scroll head)))
    (t/is (= (thi/id :heart-main) (:shape-ref head)))
    (t/is (= #{:constraints-group} (:touched head)))))

(t/deftest detach-advances-a-deeper-swapped-head-by-position
  (let [file  (setup-two-levels)
        dot   (first-child file :outer-star)
        file' (-> file
                  (detach :outer-copy)
                  (thf/validate-file!))
        head  (ths/get-shape file' :outer-star)]
    (t/is (= (thi/id :card-star) (:shape-ref head)))
    (t/is (ctk/swapped? head))
    (t/is (= (:id (thc/get-component file' :badge)) (:component-id head)))
    ;; the children of the swapped head keep referring into its component
    (t/is (= (thi/id :badge-dot) (:shape-ref dot)))
    (t/is (= (thi/id :badge-dot) (:shape-ref (ths/get-shape-by-id file' (:id dot)))))))

(t/deftest paste-makes-a-swapped-head-a-copy-of-its-component
  (let [file    (setup)
        objects (copy-objects file :copy-star)
        head    (get objects (thi/id :copy-star))
        child   (get objects (first (:shapes head)))]
    (t/is (= (thi/id :heart-main) (:shape-ref head)))
    (t/is (not (ctk/swapped? head)))
    (t/is (= (thi/id :heart-rect) (:shape-ref child)))))

(t/deftest paste-advances-a-deeper-swapped-head-by-position
  (let [file    (setup-two-levels)
        dot-id  (:id (first-child file :outer-star))
        objects (copy-objects file :outer-inner)
        head    (get objects (thi/id :outer-star))]
    (t/is (= (thi/id :card-main) (:shape-ref (get objects (thi/id :outer-inner)))))
    (t/is (= (thi/id :card-star) (:shape-ref head)))
    (t/is (ctk/swapped? head))
    (t/is (= (thi/id :badge-dot) (:shape-ref (get objects dot-id))))))

(t/deftest duplicate-keeps-the-swap
  (let [file   (-> (setup)
                   (tho/duplicate-shape :copy)
                   (thf/validate-file!))
        page   (thf/current-page file)
        copies (->> (vals (:objects page))
                    (filter #(and (ctk/instance-root? %)
                                  (= (thi/id :card-main) (:shape-ref %)))))
        heads  (map #(ths/get-shape-by-id file (first (:shapes %))) copies)]
    (t/is (= 2 (count copies)))
    (t/is (every? ctk/swapped? heads))
    (t/is (every? #(= (thi/id :card-star) (:shape-ref %)) heads))))

(defn- setup-variants
  []
  ;; :variant [:name Board]
  ;;     {:m02} [:name Board]              # [Component :c02]
  ;;         :child2                       @--> [Component :star]
  ;;     {:m01} [:name Board]              # [Component :c01]
  ;;         :child1                       @--> [Component :star]
  ;; :copy01 [:name Board]                 #--> [Component :c01] :m01
  ;;     :copy-child                       @--> [Component :heart] :child1 (swapped)
  (-> (setup)
      (thv/add-variant-with-copy :variant :c01 :m01 :c02 :m02 :child1 :child2 :star)
      (thc/instantiate-component :c01 :copy01 :children-labels [:copy-child])
      (tho/swap-component-in-shape :copy-child :heart)))

(t/deftest switch-keeps-the-swap-in-the-new-variant
  (let [file (-> (setup-variants)
                 (tho/swap-component-in-shape :copy01 :c02 {:keep-touched? true}))
        copy (ths/get-shape file :copy01)
        head (ths/get-shape file :copy-child)]
    (t/is (= (thi/id :m02) (:shape-ref copy)))
    (t/is (= [(thi/id :copy-child)] (:shapes copy)))
    (t/is (= (thi/id :child2) (:shape-ref head)))
    (t/is (ctk/swapped? head))
    (t/is (= (:id (thc/get-component file :heart)) (:component-id head)))
    (t/is (= "#222222" (tho/bottom-fill-color file :copy01)))))

(t/deftest switch-to-a-variant-with-the-same-component-ends-the-swap
  (let [file (-> (setup-variants)
                 (tho/swap-component-in-shape :child2 :heart)
                 (tho/swap-component-in-shape :copy01 :c02 {:keep-touched? true}))
        head (ths/get-shape file :copy-child)]
    (t/is (= (thi/id :child2) (:shape-ref head)))
    (t/is (not (ctk/swapped? head)))
    (t/is (= (:id (thc/get-component file :heart)) (:component-id head)))))

(t/deftest switch-to-a-variant-with-the-same-component-refers-to-its-children
  (let [file  (-> (setup-variants)
                  (tho/swap-component-in-shape :child2 :heart)
                  (tho/swap-component-in-shape :copy01 :c02 {:keep-touched? true}))
        child (first-child file :copy-child)
        file' (-> file
                  (tho/update-color :heart-rect "#333333")
                  (propagate :heart :c02)
                  (thf/validate-file!))]
    (t/is (= (:id (first-child file :child2)) (:shape-ref child)))
    ;; sync keeps the child, so its overrides stay
    (t/is (= [(:id child)] (:shapes (ths/get-shape file' :copy-child))))
    (t/is (= "#333333" (tho/bottom-fill-color file' :copy01)))))

(t/deftest switch-does-not-carry-a-swap-made-in-the-main
  ;; :variant2 [:name Board]
  ;;     {:n02} [:name Board]              # [Component :d02]
  ;;         :cardc2                       @--> [Component :card]
  ;;     {:n01} [:name Board]              # [Component :d01]
  ;;         :cardc1                       @--> [Component :card]
  ;;             :cardc1-star              @--> [Component :heart] (swapped)
  ;; :copyd [:name Board]                  #--> [Component :d01] :n01
  (let [file  (-> (setup)
                  (thv/add-variant-with-copy :variant2 :d01 :n01 :d02 :n02 :cardc1 :cardc2 :card))
        _     (thi/set-id! :cardc1-star (:id (first-child file :cardc1)))
        file  (-> file
                  (tho/swap-component-in-shape :cardc1-star :heart)
                  (thc/instantiate-component :d01 :copyd)
                  (tho/swap-component-in-shape :copyd :d02 {:keep-touched? true}))
        card  (first-child file :copyd)
        star  (ths/get-shape-by-id file (first (:shapes card)))]
    (t/is (= (thi/id :cardc2) (:shape-ref card)))
    (t/is (= (:id (thc/get-component file :star)) (:component-id star)))
    (t/is (not (ctk/swapped? star)))
    (t/is (= "#111111" (tho/bottom-fill-color file :copyd)))))

(defn- update-main
  "Push the overrides of the copy to its main, and sync the other copies."
  [file label component-label]
  (let [page      (thf/current-page file)
        container (ctn/make-container page :page)
        changes   (-> (pcb/empty-changes)
                      (pcb/with-container container)
                      (cll/generate-sync-shape-inverse {(:id file) file}
                                                       container
                                                       (thi/id label)))]
    (-> file
        (thf/apply-changes changes :validate? false)
        (propagate component-label)
        (thf/validate-file!))))

(t/deftest update-main-replaces-the-child-with-the-swapped-one
  (let [file   (-> (setup)
                   (thc/instantiate-component :card :copy2 :children-labels [:copy2-star])
                   (update-main :copy :card))
        main   (first-child file :card-main)
        head   (ths/get-shape file :copy-star)
        other  (first-child file :copy2)]
    (t/is (nil? (ths/get-shape file :card-star)))
    (t/is (= (:id (thc/get-component file :heart)) (:component-id main)))
    (t/is (= (thi/id :heart-main) (:shape-ref main)))
    (t/is (not (ctk/swapped? main)))
    (t/is (= (:id main) (:shape-ref head)))
    (t/is (not (ctk/swapped? head)))
    ;; the other copy follows the main
    (t/is (= (:id (thc/get-component file :heart)) (:component-id other)))
    (t/is (= "#222222" (tho/bottom-fill-color file :copy2)))))

(t/deftest update-main-keeps-a-deeper-swap-as-a-swap
  (let [file  (-> (setup-two-levels)
                  (update-main :outer-copy :page))
        main  (first-child file :inner-copy)
        head  (ths/get-shape file :outer-star)]
    (t/is (nil? (ths/get-shape file :inner-star)))
    (t/is (= (:id (thc/get-component file :badge)) (:component-id main)))
    (t/is (= (thi/id :card-star) (:shape-ref main)))
    (t/is (ctk/swapped? main))
    (t/is (= (:id main) (:shape-ref head)))
    (t/is (not (ctk/swapped? head)))))

(t/deftest update-main-of-a-swap-back-to-the-deeper-component-is-a-plain-copy
  ;; {:page-main}                      # [Component :page]
  ;;     :inner-copy                   @--> [Component :card]
  ;;         :inner-star               @--> [Component :heart] (swapped)
  ;; :outer-copy                       #--> [Component :page]
  ;;     :outer-inner                  @--> :inner-copy
  ;;         :outer-star               @--> [Component :star] (swapped back)
  (let [file  (-> (setup)
                  (tho/add-frame :page-main)
                  (thc/instantiate-component :card :inner-copy
                                             :parent-label :page-main
                                             :children-labels [:inner-star])
                  (tho/swap-component-in-shape :inner-star :heart)
                  (thc/make-component :page :page-main)
                  (thc/instantiate-component :page :outer-copy
                                             :children-labels [:outer-inner :outer-star])
                  (tho/swap-component-in-shape :outer-star :star)
                  (update-main :outer-copy :page))
        main  (first-child file :inner-copy)
        head  (ths/get-shape file :outer-star)]
    (t/is (= (:id (thc/get-component file :star)) (:component-id main)))
    (t/is (= (thi/id :card-star) (:shape-ref main)))
    (t/is (not (ctk/swapped? main)))
    (t/is (= (:id (first-child file :card-star))
             (:shape-ref (ths/get-shape-by-id file (first (:shapes main))))))
    (t/is (= (:id main) (:shape-ref head)))
    (t/is (not (ctk/swapped? head)))))

(t/deftest update-main-of-a-swapped-head-updates-its-own-component
  (let [file  (setup)
        _     (thi/set-id! :copy-star-rect (:id (first-child file :copy-star)))
        file' (-> file
                  (tho/update-color :copy-star-rect "#555555")
                  (update-main :copy-star :heart))]
    (t/is (= "#555555" (tho/bottom-fill-color file' :heart-main)))
    (t/is (= "#111111" (tho/bottom-fill-color file' :star-main)))
    (t/is (ctk/swapped? (ths/get-shape file' :copy-star)))))

(t/deftest swap-back-to-the-replaced-component-is-a-plain-copy
  (let [file  (-> (setup)
                  (tho/swap-component-in-shape :copy-star :star))
        head  (ths/get-shape file :copy-star)
        child (first-child file :copy-star)]
    (t/is (= (thi/id :card-star) (:shape-ref head)))
    (t/is (not (ctk/swapped? head)))
    (t/is (= (:id (first-child file :card-star)) (:shape-ref child)))
    (t/is (= "#666666" (-> file
                           (tho/update-color :star-rect "#666666")
                           (propagate :star :card)
                           (thf/validate-file!)
                           (tho/bottom-fill-color :copy))))))

(t/deftest swap-in-a-main-is-a-plain-nested-copy
  (let [file (-> (setup)
                 (tho/swap-component-in-shape :card-star :heart))
        head (ths/get-shape file :card-star)]
    (t/is (= (thi/id :heart-main) (:shape-ref head)))
    (t/is (not (ctk/swapped? head)))
    (t/is (empty? (:touched head)))))

(t/deftest swapped-head-takes-the-flex-item-attrs-of-the-replaced-child
  (let [margin {:m1 7 :m2 7 :m3 7 :m4 7}
        file   (-> (thf/sample-file :file1)
                   (tho/add-simple-component :star :star-main :star-rect)
                   (tho/add-simple-component :heart :heart-main :heart-rect)
                   (tho/add-frame :card-main :layout :flex :layout-flex-dir :row)
                   (thc/instantiate-component :star :card-star :parent-label :card-main)
                   (thc/make-component :card :card-main)
                   (thc/instantiate-component :card :copy :children-labels [:copy-star])
                   (tho/swap-component-in-shape :copy-star :heart)
                   (ths/update-shape :card-star :layout-item-margin margin)
                   (propagate :card)
                   (thf/validate-file!))]
    (t/is (= margin (:layout-item-margin (ths/get-shape file :copy-star))))))

(t/deftest switch-places-the-swap-where-the-new-variant-has-its-child
  (let [file  (-> (setup-variants)
                  (move-shape :child2 30 5))
        rel   (fn [file child parent]
                (let [[x y] (position file child)
                      [px py] (position file parent)]
                  [(- x px) (- y py)]))
        file' (tho/swap-component-in-shape file :copy01 :c02 {:keep-touched? true})]
    (t/is (ctk/swapped? (ths/get-shape file' :copy-child)))
    (t/is (= (rel file' :child2 :m02) (rel file' :copy-child :copy01)))))

(t/deftest undo-of-a-switch-brings-the-swap-back
  (let [file      (-> (setup-variants)
                      (move-shape :child2 30 5))
        page      (thf/current-page file)
        shape     (ths/get-shape file :copy01)
        libraries {(:id file) file}
        orig      (cfh/get-children-with-self (:objects page) (:id shape))
        [new-shape _ changes]
        (clsw/generate-component-swap (pcb/empty-changes nil (:id page))
                                      (:objects page) shape (:data file) page libraries
                                      (:id (thc/get-component file :c02)) 0 nil {} true)
        [changes _] (clv/generate-keep-touched changes new-shape shape orig page libraries (:data file))
        file'     (thf/apply-changes file changes)
        file''    (thf/apply-undo-changes file' changes)]
    (t/is (= [(thi/id :copy-child)] (:shapes (ths/get-shape file' :copy01))))
    (t/is (= [(thi/id :copy-child)] (:shapes (ths/get-shape file'' :copy01))))
    (t/is (= (thi/id :child1) (:shape-ref (ths/get-shape file'' :copy-child))))
    (t/is (ctk/swapped? (ths/get-shape file'' :copy-child)))
    (t/is (not= (position file :copy-child) (position file' :copy-child)))
    (t/is (= (position file :copy-child) (position file'' :copy-child)))))

(defn- transform-subtree
  "Apply the modifiers `f` gives for each shape to the shape and its children,
  as rotating or flipping it in the editor does."
  [file label f]
  (let [page    (thf/current-page file)
        objects (:objects page)
        shape   (get objects (thi/id label))
        center  (gsh/shape->center shape)
        ids     (set (cfh/get-children-ids-with-self objects (:id shape)))
        changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                            ids
                                            #(gsh/transform-shape % (f % center))
                                            objects
                                            {})]
    (thf/apply-changes file changes)))

(defn- setup-sized
  []
  ;; :star and :heart are 40x40 frames with a 30x30 child; :holder is a plain
  ;; frame with a copy of :star; :copy is a copy of :card, which nests :star
  (-> (thf/sample-file :file1)
      (tho/add-simple-component :star :star-main :star-rect
                                :root-params {:width 40 :height 40}
                                :child-params {:width 30 :height 30})
      (tho/add-simple-component :heart :heart-main :heart-rect
                                :root-params {:width 40 :height 40}
                                :child-params {:width 30 :height 30 :type :circle})
      (tho/add-frame :holder :width 200 :height 100)
      (thc/instantiate-component :star :plain-star :parent-label :holder)
      (tho/add-frame :card-main :width 200 :height 100)
      (thc/instantiate-component :star :card-star :parent-label :card-main)
      (thc/make-component :card :card-main)
      (thc/instantiate-component :card :copy :children-labels [:copy-star])))

(t/deftest swapping-a-nested-copy-takes-constraints-from-its-position
  (let [initial (-> (tm/make-situation (setup-sized))
                    (r/step (n/change-property :card-star :constraints-h :right))
                    (r/step (n/change-property :card-star :constraints-v :bottom))
                    (r/step (n/change-property :card-star :fixed-scroll true))
                    r/settle
                    (r/step (n/change-property :copy-star :constraints-h :scale))
                    (r/step (n/change-property :copy-star :constraints-v :scale))
                    (r/step (n/change-property :copy-star :fixed-scroll false))
                    r/settle)
        swapped (-> initial (r/step (n/swap-shape :copy-star :heart)) r/settle)
        head (tm/resolve-shape swapped :copy-star)
        placement #(select-keys % [:constraints-h :constraints-v :fixed-scroll])
        synced (tm/with-file swapped (propagate (tm/file swapped) :card))
        undone (r/step swapped (n/undo))
        redone (r/step undone (n/redo))]
    (t/is (= {:constraints-h :right :constraints-v :bottom :fixed-scroll true}
             (placement head)))
    (t/is (= #{:swap-group} (:touched head)))
    (t/is (= (placement head) (placement (tm/resolve-shape synced :copy-star))))
    (t/is (= (thn/normalize-file (tm/file initial))
             (thn/normalize-file (tm/file undone))))
    (t/is (= (thn/normalize-file (tm/file swapped))
             (thn/normalize-file (tm/file redone))))))

(t/deftest swapping-a-nested-copy-clears-content-main-constraints-at-a-default-position
  (let [initial (-> (tm/make-situation (setup-sized))
                    (r/step (n/change-property :heart-main :constraints-h :right))
                    (r/step (n/change-property :heart-main :constraints-v :bottom))
                    (r/step (n/change-property :heart-main :fixed-scroll true))
                    r/settle)
        swapped (-> initial (r/step (n/swap-shape :copy-star :heart)) r/settle)
        placement #(select-keys % [:constraints-h :constraints-v :fixed-scroll])]
    (t/is (= (placement (tm/resolve-shape initial :card-star))
             (placement (tm/resolve-shape swapped :copy-star))))
    (t/is (= #{:swap-group} (:touched (tm/resolve-shape swapped :copy-star))))))

(t/deftest switching-a-nested-copy-retains-compatible-authored-constraints
  (let [initial (-> (tm/make-situation (setup-sized))
                    (r/step (n/change-property :card-star :constraints-v :bottom))
                    r/settle
                    (r/step (n/change-property :copy-star :constraints-v :scale))
                    r/settle)
        switched (-> initial (r/step (n/swap-shape :copy-star :heart :keep-touched? true)) r/settle)
        head (tm/resolve-shape switched :copy-star)]
    (t/is (= :scale (:constraints-v head)))
    (t/is (= #{:swap-group :constraints-group} (:touched head)))
    (t/is (= (thn/normalize-file (tm/file initial))
             (thn/normalize-file (tm/file (r/step switched (n/undo))))))))

(defn- orientation
  [file label]
  (let [shape (ths/get-shape file label)]
    {:rotation (mth/precision (d/nilv (:rotation shape) 0) 2)
     :flip-x   (true? (:flip-x shape))
     :center   (let [c (gsh/shape->center shape)] [(mth/precision (:x c) 2) (mth/precision (:y c) 2)])}))

(t/deftest swap-keeps-the-rotation-of-the-replaced-shape
  (doseq [[parent child] [[:holder :plain-star] [:copy :copy-star]]]
    (let [file  (transform-subtree (setup-sized) parent #(ctm/rotation-modifiers %1 %2 30))
          file' (tho/swap-component-in-shape file child :heart)
          head  (ths/get-shape file' child)]
      (t/is (== 30 (:rotation (orientation file child))))
      (t/is (= (orientation file child) (orientation file' child)))
      (t/is (== 30 (mth/precision (:rotation (ths/get-shape-by-id file' (first (:shapes head)))) 2)))
      (t/is (not (ctk/touched-group? head :geometry-group))))))

(t/deftest swap-keeps-the-flip-of-the-replaced-shape
  (let [file  (transform-subtree (setup-sized) :copy
                                 (fn [_ center] (ctm/resize-modifiers (gpt/point -1 1) center)))
        file' (tho/swap-component-in-shape file :copy-star :heart)]
    (t/is (:flip-x (orientation file :copy-star)))
    (t/is (= (orientation file :copy-star) (orientation file' :copy-star)))))

(t/deftest switch-keeps-the-rotation-of-a-rotated-copy
  (let [file  (-> (thf/sample-file :file1)
                  (tho/add-simple-component :star :star-main :star-rect
                                            :root-params {:width 40 :height 40}
                                            :child-params {:width 30 :height 30})
                  (thv/add-variant-with-copy :variant :c01 :m01 :c02 :m02 :child1 :child2 :star)
                  (thc/instantiate-component :c01 :copy01 :children-labels [:copy-child])
                  (transform-subtree :copy01 #(ctm/rotation-modifiers %1 %2 30)))
        file' (tho/swap-component-in-shape file :copy01 :c02 {:keep-touched? true})
        copy  (ths/get-shape file' :copy01)
        child (ths/get-shape-by-id file' (first (:shapes copy)))]
    (t/is (== 30 (mth/precision (:rotation copy) 2)))
    (t/is (== 30 (mth/precision (:rotation child) 2)))
    (t/is (ctk/touched-group? copy :geometry-group))
    (t/is (= (orientation file' :copy01)
             (orientation (propagate file' :c02) :copy01)))))

(t/deftest switch-turns-the-swap-with-the-child-of-the-new-variant
  (let [file  (-> (thf/sample-file :file1)
                  (tho/add-simple-component :star :star-main :star-rect
                                            :root-params {:width 40 :height 40}
                                            :child-params {:width 30 :height 30})
                  (tho/add-simple-component :heart :heart-main :heart-rect
                                            :root-params {:width 40 :height 40}
                                            :child-params {:width 30 :height 30})
                  (thv/add-variant-with-copy :variant :c01 :m01 :c02 :m02 :child1 :child2 :star)
                  (transform-subtree :child2 #(ctm/rotation-modifiers %1 %2 30))
                  (thc/instantiate-component :c01 :copy01 :children-labels [:copy-child])
                  (tho/swap-component-in-shape :copy-child :heart))
        file' (-> file
                  (tho/swap-component-in-shape :copy01 :c02 {:keep-touched? true}))
        head  (ths/get-shape file' :copy-child)]
    (t/is (ctk/swapped? head))
    (t/is (== 30 (:rotation (orientation file' :copy-child))))
    (t/is (== 30 (mth/precision (:rotation (ths/get-shape-by-id file' (first (:shapes head)))) 2)))))

(t/deftest swapped-head-follows-the-rotation-of-the-replaced-child
  (let [file  (-> (setup-sized)
                  (tho/swap-component-in-shape :copy-star :heart))
        file' (-> file
                  (transform-subtree :card-star #(ctm/rotation-modifiers %1 %2 30))
                  (ths/update-shape :card-main :opacity 0.9)
                  (propagate :card)
                  (thf/validate-file!))
        head  (ths/get-shape file' :copy-star)]
    (t/is (== 30 (:rotation (orientation file' :card-star))))
    (t/is (== 30 (:rotation (orientation file' :copy-star))))
    (t/is (== 30 (mth/precision (:rotation (ths/get-shape-by-id file' (first (:shapes head)))) 2)))
    (t/is (ctk/swapped? head))
    (t/is (not (ctk/touched-group? head :geometry-group)))))

(t/deftest swapped-head-follows-the-flip-of-the-replaced-child
  (let [file' (-> (setup-sized)
                  (tho/swap-component-in-shape :copy-star :heart)
                  (transform-subtree :card-star (fn [_ center] (ctm/resize-modifiers (gpt/point -1 1) center)))
                  (ths/update-shape :card-main :opacity 0.9)
                  (propagate :card)
                  (thf/validate-file!))]
    (t/is (:flip-x (orientation file' :card-star)))
    (t/is (:flip-x (orientation file' :copy-star)))))

(t/deftest rotated-swapped-head-keeps-its-rotation
  ;; the user rotated the swapped head: the main's rotation does not reach it
  (let [file' (-> (setup-sized)
                  (tho/swap-component-in-shape :copy-star :heart)
                  (transform-subtree :copy-star #(ctm/rotation-modifiers %1 %2 10))
                  (transform-subtree :card-star #(ctm/rotation-modifiers %1 %2 30))
                  (ths/update-shape :card-main :opacity 0.9)
                  (propagate :card)
                  (thf/validate-file!))]
    (t/is (== 10 (:rotation (orientation file' :copy-star))))))

(t/deftest plain-swap-keeps-authored-rotation-as-a-geometry-override
  (doseq [target [:plain-star :card-star :copy-star]]
    (let [initial (tm/make-situation (setup-sized))
          rotated (-> initial (r/step (n/rotate target 45)) r/settle
                      (r/step (n/change-property target :opacity 0.4)) r/settle)
          swapped (-> rotated (r/step (n/swap-shape target :heart)) r/settle)
          head (tm/resolve-shape swapped target)
          file (tm/file swapped)
          full (:file (r/sync-cascade file {} (keys (get-in file [:data :components]))))]
      (t/is (ctk/touched-group? head :geometry-group))
      (t/is (== 45 (:rotation head)))
      (t/is (not (ctk/touched-group? head :layer-effects-group)))
      (t/is (= 1 (:opacity head 1)))
      (t/is (= (orientation file target) (orientation full target))))))

(t/deftest plain-swap-drops-a-size-only-override
  (let [situation (-> (tm/make-situation (setup-sized))
                      (r/step (n/change-height :plain-star 80)) r/settle
                      (r/step (n/swap-shape :plain-star :heart)) r/settle)
        head (tm/resolve-shape situation :plain-star)]
    (t/is (== 40 (:height head)))
    (t/is (not (ctk/touched-group? head :geometry-group)))))

(t/deftest swapped-copy-keeps-position-orientation-when-its-content-main-turns
  (let [initial (setup-sized)
        file (-> initial
                 (tho/swap-component-in-shape :copy-star :heart)
                 (transform-subtree :heart-main #(ctm/rotation-modifiers %1 %2 90))
                 (propagate :heart))
        head (ths/get-shape file :copy-star)
        child (ths/get-shape-by-id file (first (:shapes head)))]
    (t/is (mth/close? 0 (:rotation head) 0.001))
    (t/is (mth/close? 0 (:rotation child) 0.001))
    (t/is (= #{:swap-group} (:touched head)))
    (t/is (nil? (:touched child)))))

(t/deftest plain-swap-keeps-the-orientation-inherited-from-its-old-component
  (let [initial (-> (tm/make-situation (setup-sized))
                    (r/step (n/rotate :star-main 45)) r/settle)
        swapped (-> initial (r/step (n/swap-shape :card-star :heart)) r/settle)
        head (tm/resolve-shape swapped :card-star)
        file (tm/file swapped)
        full (:file (r/sync-cascade file {} (keys (get-in file [:data :components]))))]
    (t/is (nil? (:touched (tm/resolve-shape initial :card-star))))
    (t/is (ctk/touched-group? head :geometry-group))
    (t/is (mth/close? 45 (:rotation head) 0.001))
    (t/is (= (orientation file :card-star) (orientation full :card-star)))))

(t/deftest switch-keeps-root-orientation-authored-in-a-near-main
  (let [initial (reduce #(tm/apply-to %2 %1) (setups/empty-situation)
                        [(n/create-component "variant" setups/labeled-fill :size 100)
                         (n/make-variant-container "variants"
                                                   [["a" setups/labeled-fill]
                                                    ["b" setups/labeled-fill]] :size 100)
                         (n/make-nested-component-with-variant "variant" "variants" "a")
                         (n/make-nested-component "variant" 1)])
        source (n/nested-head-of "variant" 0)
        target (n/nested-head-of "variant" 1)
        before (-> initial (r/step (n/rotate source 45)) r/settle)
        swapped (-> before (r/step (n/switch-variant target "b")) r/settle)
        head (tm/shape-by-id swapped (target swapped))
        file (tm/file swapped)
        full (:file (r/sync-cascade file {} (keys (get-in file [:data :components]))))]
    (t/is (nil? (:touched (tm/shape-by-id before (target before)))))
    (t/is (ctk/touched-group? head :geometry-group))
    (t/is (mth/close? 45 (:rotation head) 0.001))
    (t/is (gsh/close-attrs? :points (:points head) (:points (ths/get-shape-by-id full (:id head))) 0.001))))

(t/deftest update-main-clears-overrides-on-the-swapped-subtree-it-pushes
  (let [edited (-> (tm/make-situation (setup-sized))
                   (r/step (n/swap-shape :copy-star :heart)) r/settle
                   (r/step (n/change-height :copy-star 80)) r/settle
                   (r/step (n/change-property :copy-star :opacity 0.4)) r/settle)
        updated (-> edited (r/step (n/update-main :copy)) r/settle)
        head (tm/resolve-shape updated :copy-star)
        main (tm/shape-by-id updated (:shape-ref head))]
    (t/is (empty? (:touched head)))
    (t/is (= #{:geometry-group :layer-effects-group} (:touched main)))
    (t/is (= 0.4 (:opacity head) (:opacity main)))
    (t/is (mth/close? 80 (:height head) 0.001))
    (t/is (= (thn/normalize-file (tm/file edited))
             (thn/normalize-file (tm/file (r/step updated (n/undo))))))))

(t/deftest detaching-a-main-child-removes-the-copys-now-invalid-swap
  (let [initial (tm/make-situation (setup))
        detached (-> initial (r/step (n/detach :card-star)) r/settle)
        head (tm/resolve-shape detached :copy-star)
        main (tm/resolve-shape detached :card-star)
        objects (:objects (thf/current-page (tm/file detached)))]
    (t/is (not (ctk/instance-head? head)))
    (t/is (not (ctk/swapped? head)))
    (t/is (= (:shapes main) (mapv #(get-in objects [% :shape-ref]) (:shapes head))))
    (t/is (= (thn/normalize-file (tm/file initial))
             (thn/normalize-file (tm/file (r/step detached (n/undo))))))))

(t/deftest plain-swap-does-not-author-the-positions-inherited-orientation
  (let [rotated (-> (tm/make-situation (setup-sized))
                    (r/step (n/rotate :card-star 45)) r/settle)
        swapped (-> rotated (r/step (n/swap-shape :copy-star :heart)) r/settle)
        head (tm/resolve-shape swapped :copy-star)]
    (t/is (mth/close? 45 (:rotation head) 0.001))
    (t/is (= #{:swap-group} (:touched head)))))

(t/deftest removing-the-last-swap-group-leaves-no-empty-override-field
  (let [shape {:touched #{:swap-group}}]
    (t/is (not (contains? (ctk/remove-swap-group shape) :touched)))
    (t/is (= #{:geometry-group}
             (:touched (ctk/remove-swap-group (assoc shape :touched #{:swap-group :geometry-group})))))))

(t/deftest moving-a-swapped-subtree-pushed-into-a-main-undoes-exactly
  (let [updated (-> (tm/make-situation (setup))
                    (r/step (n/update-main :copy)) r/settle)
        file (tm/file updated)
        head (tm/shape-by-id updated (:shape-ref (tm/resolve-shape updated :copy-star)))
        moved (-> updated (r/step (n/nest-copy (constantly (:id head)) :star-main)) r/settle)
        undone (r/step moved (n/undo))]
    (t/is (nil? (:touched head)))
    (t/is (= (thn/normalize-file file) (thn/normalize-file (tm/file undone))))))

(t/deftest swapping-a-child-restores-its-parents-remote-sync-flag-on-undo
  (let [initial (tm/make-situation (ths/update-shape (setup-sized) :copy :remote-synced true))
        swapped (-> initial (r/step (n/swap-shape :copy-star :heart)) r/settle)
        undone (r/step swapped (n/undo))]
    (t/is (:remote-synced (tm/resolve-shape initial :copy)))
    (t/is (not (:remote-synced (tm/resolve-shape swapped :copy))))
    (t/is (:remote-synced (tm/resolve-shape undone :copy)))
    (t/is (= (thn/normalize-file (tm/file initial))
             (thn/normalize-file (tm/file undone))))))

(t/deftest structural-child-changes-restore-the-parents-remote-sync-state
  (doseq [operation [:add :remove]]
    (let [file (ths/update-shape (setup-sized) :copy-star :remote-synced true)
          page (thf/current-page file)
          parent (ths/get-shape file :copy-star)
          changes (-> (pcb/empty-changes nil (:id page))
                      (pcb/with-objects (:objects page)))
          changes (case operation
                    :add (pcb/add-object changes (ths/sample-shape :added
                                                                   :parent-id (:id parent)
                                                                   :frame-id (:id parent)))
                    :remove (pcb/remove-objects changes [(first (:shapes parent))]))
          changed (thf/apply-changes file changes :validate? false)
          undone (thf/apply-undo-changes changed changes)]
      (t/is (not (:remote-synced (ths/get-shape changed :copy-star))) (name operation))
      (t/is (= (thn/normalize-file file) (thn/normalize-file undone)) (name operation)))))

(t/deftest removing-several-siblings-restores-their-order-on-undo
  (let [file (reduce (fn [file label] (tho/add-rect file label {:parent-label :parent}))
                     (tho/add-frame (thf/sample-file :file1) :parent) [:a :b :c :d :e])
        page (thf/current-page file)]
    (doseq [labels [[:b :c :d] [:b :d :c] [:c :b :d] [:c :d :b] [:d :b :c] [:d :c :b]]]
      (let [changes (-> (pcb/empty-changes nil (:id page))
                        (pcb/with-objects (:objects page))
                        (pcb/remove-objects (mapv thi/id labels)))
            removed (thf/apply-changes file changes)
            undone (thf/apply-undo-changes removed changes)]
        (t/is (= (:shapes (ths/get-shape file :parent))
                 (:shapes (ths/get-shape undone :parent))) (pr-str labels))))))

(t/deftest syncing-a-main-swap-restores-all-removed-siblings-in-order-on-undo
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-simple-component :star :star-main :star-rect)
                 (tho/add-rect :extra-a {:parent-label :star-main})
                 (tho/add-rect :extra-b {:parent-label :star-main})
                 (tho/add-rect :extra-c {:parent-label :star-main})
                 (tho/add-simple-component :heart :heart-main :heart-rect)
                 (tho/add-frame :card-main)
                 (thc/instantiate-component :star :card-star :parent-label :card-main)
                 (thc/make-component :card :card-main)
                 (thc/instantiate-component :card :copy :children-labels [:copy-star]))
        initial (tm/make-situation file)
        swapped (-> initial (r/step (n/swap-shape :card-star :heart)) r/settle)
        undone (r/step swapped (n/undo))]
    (t/is (= (thn/normalize-file file) (thn/normalize-file (tm/file undone))))))

(t/deftest inherited-content-overrides-stop-at-a-swapped-reference
  (let [file (-> (thf/sample-file :file)
                 (tho/add-two-levels-nested-component-with-copy
                  :c1 :main1 :main1-child :c2 :main2 :nested-head1
                  :c3 :main3 :nested-head2 :nested-subhead2 :copy3)
                 (tho/add-simple-component :other :other-main :other-child))
        file (-> (tm/make-situation file)
                 (r/step (n/change-property :nested-head1 :opacity 0.4))
                 tm/file
                 (tho/swap-component-in-shape :nested-subhead2 :other
                                              :propagate-fn #(propagate % :c3)))
        page (thf/current-page file)
        middle (ths/get-shape-by-id file (first (:shapes (ths/get-shape file :copy3))))
        child (ths/get-shape-by-id file (first (:shapes middle)))
        libraries {(:id file) file}
        chain (ctf/get-ref-chain-until-target-ref page libraries child nil)]
    (t/is (= [(:id child) (thi/id :nested-subhead2) (thi/id :other-main)]
             (mapv :id chain)))
    (t/is (= #{:swap-group}
             (ctf/get-touched-from-ref-chain-until-target-ref page libraries child nil)))))

(t/deftest update-main-authors-a-swaps-inherited-orientation-in-its-new-main
  (doseq [angle [0 45]]
    (let [initial (-> (tm/make-situation (setup-sized))
                      (r/step (n/rotate :card-star angle)) r/settle)
          swapped (-> initial (r/step (n/swap-shape :copy-star :heart)) r/settle)
          updated (-> swapped (r/step (n/update-main :copy)) r/settle)
          file (tm/file updated)
          head (tm/resolve-shape updated :copy-star)
          main (tm/shape-by-id updated (:shape-ref head))
          full (:file (r/sync-cascade file {} (keys (get-in file [:data :components]))))]
      (t/is (= (if (zero? angle) #{} #{:geometry-group}) (set (:touched main))))
      (t/is (mth/close? angle (:rotation main) 0.001))
      (t/is (mth/close? angle (:rotation (ths/get-shape-by-id full (:id main))) 0.001))
      (t/is (gsh/close-attrs? :points (:points head)
                              (:points (ths/get-shape-by-id full (:id head))) 0.001))
      (t/is (= (thn/normalize-file (tm/file swapped))
               (thn/normalize-file (tm/file (r/step updated (n/undo)))))))))

(t/deftest pushing-a-swap-uses-the-parents-updated-orientation
  (let [swapped (-> (tm/make-situation (setup-sized))
                    (r/step (n/swap-shape :copy-star :heart)) r/settle
                    (r/step (n/rotate :copy 45)) r/settle)
        updated (-> swapped (r/step (n/update-main :copy)) r/settle)
        file (tm/file updated)
        head (tm/resolve-shape updated :copy-star)
        main (tm/shape-by-id updated (:shape-ref head))
        full (:file (r/sync-cascade file {} (keys (get-in file [:data :components]))))]
    (t/is (empty? (:touched main)))
    (t/is (mth/close? 45 (:rotation main) 0.001))
    (t/is (gsh/close-attrs? :points (:points head)
                            (:points (ths/get-shape-by-id full (:id head))) 0.001))
    (t/is (= (thn/normalize-file (tm/file swapped))
             (thn/normalize-file (tm/file (r/step updated (n/undo))))))))

(t/deftest pushing-a-swap-authors-content-that-differs-from-its-new-position
  (let [file (-> (setup)
                 (tho/add-frame :page-main)
                 (thc/instantiate-component :card :inner-copy
                                            :parent-label :page-main
                                            :children-labels [:inner-star])
                 (thc/make-component :page :page-main)
                 (thc/instantiate-component :page :outer-copy
                                            :children-labels [:outer-inner :outer-star]))
        swapped (-> (tm/make-situation file)
                    (r/step (n/swap-shape :outer-star :heart)) r/settle
                    (r/step (n/swap-shape :card-star :heart)) r/settle)
        child-id (first (:shapes (tm/resolve-shape swapped :card-star)))
        edited (-> swapped
                   (r/step (n/change-property :card-star :fills "#333333")) r/settle
                   (r/step (n/change-property (constantly child-id) :fills "#444444")) r/settle)
        updated (-> edited (r/step (n/update-main :outer-copy)) r/settle)
        head (tm/resolve-shape updated :outer-star)
        main (tm/shape-by-id updated (:shape-ref head))
        main-child (tm/shape-by-id updated (first (:shapes main)))
        file (tm/file updated)
        full (:file (r/sync-cascade file {} (keys (get-in file [:data :components]))))]
    (t/is (ctk/swapped? (tm/resolve-shape edited :outer-star)))
    (t/is (= (thi/id :card-star) (:shape-ref main)))
    (t/is (= #{:fill-group} (:touched main) (:touched main-child)))
    (t/is (= (:fills (tm/resolve-shape edited :outer-star)) (:fills main)))
    (t/is (= "#222222" (get-in main-child [:fills 0 :fill-color])))
    (t/is (empty? (:touched head)))
    (t/is (= (:fills main) (:fills (ths/get-shape-by-id full (:id main)))))
    (t/is (= (:fills main-child) (:fills (ths/get-shape-by-id full (:id main-child)))))
    (t/is (= (thn/normalize-file (tm/file edited))
             (thn/normalize-file (tm/file (r/step updated (n/undo))))))))

(t/deftest swapping-back-inherits-the-new-positions-content-overrides
  (let [initial (tm/make-situation (setup))
        child-id (first (:shapes (tm/resolve-shape initial :card-star)))
        edited (-> initial
                   (r/step (n/change-property :card-star :fills "#333333")) r/settle
                   (r/step (n/change-property (constantly child-id) :fills "#444444")) r/settle)
        swapped (-> edited (r/step (n/swap-shape :copy-star :star)) r/settle)
        head (tm/resolve-shape swapped :copy-star)
        child (tm/shape-by-id swapped (first (:shapes head)))
        file (tm/file swapped)
        full (:file (r/sync-cascade file {} (keys (get-in file [:data :components]))))]
    (t/is (= "#333333" (get-in head [:fills 0 :fill-color])))
    (t/is (= "#444444" (get-in child [:fills 0 :fill-color])))
    (t/is (empty? (:touched head)))
    (t/is (empty? (:touched child)))
    (t/is (= (:fills head) (:fills (ths/get-shape-by-id full (:id head)))))
    (t/is (= (thn/normalize-file (tm/file edited))
             (thn/normalize-file (tm/file (r/step swapped (n/undo))))))))

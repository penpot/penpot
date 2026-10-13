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
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.logic.variants :as clv]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.variants :as thv]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

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
        changes (cll/generate-detach-instance (-> (pcb/empty-changes nil)
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
    (cll/advance-copied-objects file
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
                      (cll/generate-sync-shape-inverse (:data file)
                                                       {(:id file) file}
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
        (cll/generate-component-swap (pcb/empty-changes nil (:id page))
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

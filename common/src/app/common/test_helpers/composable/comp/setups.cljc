;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.test-helpers.composable.comp.setups
  "Setups of the composable component cases: functions that build a file
   and return a situation with its roles (see
   `mem:frontend/composable-component-tests`). In the one-child setups,
   :main-instance and :copy-instance are the children of the main and the
   copy."
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]))

;; The fill the labeled setups give the main child.
(def labeled-fill "#abcdef")

(defn simple-component-with-copy
  "A situation with a simple component (root + one child) and one clean copy.
   Shape labels: :main-root :main-child :copy-root :copy-child. Roles:
   :main-instance -> the main child, :copy-instance -> the copy child."
  []
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-simple-component-with-copy
                  :component1 :main-root :main-child :copy-root
                  :copy-root-params {:children-labels [:copy-child]}))]
    (tm/make-situation file {:main-instance :main-child
                             :copy-instance :copy-child
                             :main-root     :main-root
                             :copy-root     :copy-root})))


(defn flex-component-with-copy
  "Like `simple-component-with-copy`, with a 100x100 main root that has a
   flex layout (row) and a 50x50 child. Same labels and roles."
  []
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-simple-component-with-copy
                  :component1 :main-root :main-child :copy-root
                  :main-root-params {:layout :flex :layout-flex-dir :row
                                     :width 100 :height 100}
                  :main-child-params {:width 50 :height 50}
                  :copy-root-params {:children-labels [:copy-child]}))]
    (tm/make-situation file {:main-instance :main-child
                             :copy-instance :copy-child
                             :main-root     :main-root
                             :copy-root     :copy-root})))

(defn empty-situation
  "A situation with an empty file and no roles, for cases that build their
   configuration with operations (`create-component` and the like)."
  []
  (tm/make-situation (thf/sample-file :file1)))

(defn simple-component-with-labeled-copy
  "Like `simple-component-with-copy`, with the main child filled
   `labeled-fill`. Same labels and roles."
  []
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-simple-component-with-copy
                  :component1 :main-root :main-child :copy-root
                  :main-child-params {:fills (ths/sample-fills-color :fill-color labeled-fill)}
                  :copy-root-params  {:children-labels [:copy-child]}))]
    (tm/make-situation file {:main-instance :main-child
                             :copy-instance :copy-child
                             :main-root     :main-root
                             :copy-root     :copy-root})))

(defn simple-component-with-copies-on-two-pages
  "Like `simple-component-with-labeled-copy`, plus a second copy on another
   page. The main and the first copy live on the current page `:page1`; the
   second copy on `:page2`. Labels: :main-root :main-child :copy-root
   :copy-child :copy2-root :copy2-child. Roles as in the labeled setup, plus
   :other-page-copy-instance -> :copy2-child (read it with
   `other-page-copy-instance`, since it is not on the current page)."
  []
  (let [file (-> (thf/sample-file :file1 :page-label :page1)
                 (tho/add-simple-component-with-copy
                  :component1 :main-root :main-child :copy-root
                  :main-child-params {:fills (ths/sample-fills-color :fill-color labeled-fill)}
                  :copy-root-params  {:children-labels [:copy-child]})
                 (thf/add-sample-page :page2)
                 (thc/instantiate-component :component1 :copy2-root
                                            :children-labels [:copy2-child])
                 (thf/switch-to-page :page1))]
    (tm/make-situation file {:main-instance            :main-child
                             :copy-instance            :copy-child
                             :main-root                :main-root
                             :copy-root                :copy-root
                             :other-page-copy-instance :copy2-child})))

(defn other-page-copy-instance
  "The copy child on the other page (role :other-page-copy-instance), live."
  [situation]
  (tm/role-shape-in-file situation :other-page-copy-instance))

(defn simple-text-component-with-copy
  "A component whose root (100x100) holds one text, \"hello world\", and a
   clean copy. Labels: :main-root :main-text :copy-root :copy-text. Roles:
   :main-instance -> the main text, :copy-instance -> the copy text, plus
   :main-root and :copy-root."
  []
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-frame-with-text :main-root :main-text "hello world"
                                          :frame-params {:width 100 :height 100}
                                          ;; the name a new text gets from its content
                                          :child-params {:name "hello world"})
                 (thc/make-component :component1 :main-root)
                 (thc/instantiate-component :component1 :copy-root
                                            :children-labels [:copy-text]))]
    (tm/make-situation file {:main-instance :main-text
                             :copy-instance :copy-text
                             :main-root     :main-root
                             :copy-root     :copy-root})))

(defn- label-copy-children!
  "Register each label of `labels` (main child label -> copy child label) on
   the child of the copy `copy-label` that references that main child."
  [file copy-label labels]
  (let [objects (:objects (thf/current-page file))]
    (doseq [[main-label copy-child-label] labels]
      (thi/set-id! copy-child-label
                   (d/seek #(= (thi/id main-label) (get-in objects [% :shape-ref]))
                           (get-in objects [(thi/id copy-label) :shapes]))))))

(defn grid-component-with-copy
  "A component whose main is a grid board (one row, three columns, 300x100)
   holding two copies of an `:item` component in its first two columns, the
   third free, and a copy of it. A second component, `:other`, has a red rect
   (a swap target). Labels: :grid-root :child1 :child2 (main side),
   :grid-copy :copy-child1 :copy-child2 (copy side), :item, :other. Roles:
   :main-root, :copy-root, :main-child -> :child1, :copy-child -> :copy-child1."
  []
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-simple-component :item :item-root :item-rect
                                           :root-params {:width 100 :height 100}
                                           :child-params {:width 50 :height 50})
                 (tho/add-simple-component :other :other-root :other-rect
                                           :root-params {:width 100 :height 100}
                                           :child-params {:width 50 :height 50
                                                          :fills (ths/sample-fills-color
                                                                  :fill-color "#ff0000")})
                 (tho/add-grid-frame :grid-root :width 300 :height 100)
                 (thc/instantiate-component :item :child1 :parent-label :grid-root)
                 (thc/instantiate-component :item :child2 :parent-label :grid-root)
                 (tho/place-in-grid-columns :grid-root 3 {1 :child1 2 :child2})
                 (thc/make-component :grid :grid-root)
                 (thc/instantiate-component :grid :grid-copy))]
    (label-copy-children! file :grid-copy {:child1 :copy-child1 :child2 :copy-child2})
    (tm/make-situation file {:main-root  :grid-root
                             :copy-root  :grid-copy
                             :main-child :child1
                             :copy-child :copy-child1})))

(defn grouped-component-with-copy
  "A component whose main board holds a group with a copy of an `:item`
   component, and a copy of it. A second component, `:other`, has a red rect
   (a swap target). Labels: :grouped-root :group :child1 (main side),
   :grouped-copy :copy-group :copy-child1 (copy side), :item, :other. Roles:
   :main-root, :copy-root, :main-child -> :child1, :copy-child -> :copy-child1."
  []
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-simple-component :item :item-root :item-rect
                                           :root-params {:width 100 :height 100}
                                           :child-params {:width 50 :height 50})
                 (tho/add-simple-component :other :other-root :other-rect
                                           :root-params {:width 100 :height 100}
                                           :child-params {:width 50 :height 50
                                                          :fills (ths/sample-fills-color
                                                                  :fill-color "#ff0000")})
                 (tho/add-frame :grouped-root :width 200 :height 200)
                 (tho/add-group :group :parent-label :grouped-root)
                 (thc/instantiate-component :item :child1 :parent-label :group)
                 (thc/make-component :grouped :grouped-root)
                 (thc/instantiate-component :grouped :grouped-copy))]
    (label-copy-children! file :grouped-copy {:group :copy-group})
    (label-copy-children! file :copy-group {:child1 :copy-child1})
    (tm/make-situation file {:main-root  :grouped-root
                             :copy-root  :grouped-copy
                             :main-child :child1
                             :copy-child :copy-child1})))

(defn grid-component-with-copy-and-loose-item
  "`grid-component-with-copy` plus a top-level copy of `:item`, labelled
   :loose. Same roles, plus :loose."
  []
  (let [situation (grid-component-with-copy)]
    (-> situation
        (tm/with-file (thc/instantiate-component (tm/file situation) :item :loose))
        (assoc-in [:roles :loose] (thi/id :loose)))))

(defn component-with-sibling-instances
  "A component whose main (300x100) holds three copies of an `:icon`
   component side by side, and a copy of it. With `:grid` layout the main is a
   one-row, three-column grid with each copy in its own cell. Labels: :row-root
   :sub1 :sub2 :sub3 (main side), :row-copy :copy-sub1 :copy-sub2 :copy-sub3
   (copy side, matched by :shape-ref). Roles: :main-root, :copy-root."
  ([] (component-with-sibling-instances :none))
  ([layout]
   (let [grid? (= :grid layout)
         subs  [:sub1 :sub2 :sub3]
         file  (as-> (thf/sample-file :file1) $
                 (tho/add-simple-component $ :icon :icon-root :icon-rect
                                           :root-params {:width 24 :height 24}
                                           :child-params {:width 24 :height 24})
                 (if grid?
                   (tho/add-grid-frame $ :row-root :width 300 :height 100)
                   (tho/add-frame $ :row-root :width 300 :height 100))
                 (reduce #(thc/instantiate-component %1 :icon %2 :parent-label :row-root) $ subs)
                 (cond-> $
                   grid? (tho/place-in-grid-columns :row-root 3 (zipmap [1 2 3] subs)))
                 (thc/make-component $ :row :row-root)
                 (thc/instantiate-component $ :row :row-copy))]
     (label-copy-children! file :row-copy (zipmap subs [:copy-sub1 :copy-sub2 :copy-sub3]))
     (tm/make-situation file {:main-root :row-root
                              :copy-root :row-copy}))))

(defn main-instance
  "The main child shape of the configuration (role :main-instance), live."
  [situation]
  (tm/role-shape situation :main-instance))

(defn copy-instance
  "The copy child shape of the configuration (role :copy-instance), live."
  [situation]
  (tm/role-shape situation :copy-instance))

(defn main-root
  "The main component root shape (role :main-root), live."
  [situation]
  (tm/role-shape situation :main-root))

(defn copy-root
  "The copy root shape (role :copy-root), live."
  [situation]
  (tm/role-shape situation :copy-root))

(defn misaligned-sub-heads
  "The indices of the copy root's children whose :shape-ref is not the main
   root's child at the same index (positional alignment), plus `:count` when
   the copy and the main have a different number of children."
  [situation]
  (let [main (:shapes (main-root situation))
        copy (:shapes (copy-root situation))
        ref  #(:shape-ref (tm/shape-by-id situation %))]
    (cond-> (->> (map vector (range) copy main)
                 (remove (fn [[_ c m]] (= m (ref c))))
                 (mapv first))
      (not= (count copy) (count main)) (conj :count))))


(defn component-with-many-children
  "A component whose main has three children, and a copy. Labels: :main-root
   :main-child1/2/3 :copy-root :copy-child1/2/3. Roles: :main-root,
   :copy-root and :copy-child-1/2/3 -> the copy's children."
  []
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-component-with-many-children-and-copy
                  :component1
                  :main-root
                  [:main-child1 :main-child2 :main-child3]
                  :copy-root
                  :copy-root-params {:children-labels [:copy-child1 :copy-child2 :copy-child3]}))]
    (tm/make-situation file {:main-root    :main-root
                             :copy-root    :copy-root
                             :copy-child-1 :copy-child1
                             :copy-child-2 :copy-child2
                             :copy-child-3 :copy-child3})))

(defn copy-child
  "The copy child shape for 1-based index `n` (role :copy-child-n), live."
  [situation n]
  (tm/role-shape situation (keyword (str "copy-child-" n))))


(defn nested-component-with-copy
  "Component2's main holds a copy of component1 (`:main2-nested-head`), and a
   copy of component2 holds a copy of it. Labels: :main1-root :main1-child
   :main2-root :main2-nested-head :copy2-root :copy2-child. Roles:
   :main-instance -> :main2-nested-head, :copy-instance -> :copy2-child,
   :main2-root and :copy-root."
  []
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-nested-component-with-copy
                  :component1 :main1-root :main1-child
                  :component2 :main2-root :main2-nested-head
                  :copy2-root
                  :copy2-root-params {:children-labels [:copy2-child]}))]
    (tm/make-situation file {:main-instance :main2-nested-head
                             :copy-instance :copy2-child
                             :main2-root    :main2-root
                             :copy-root     :copy2-root})))


(defn- change-main-child-fill
  "`library` with its :main-child filled `fill-color`, through the production
   change path."
  [library fill-color]
  (let [page    (thf/current-page library)
        main-id (thi/id :main-child)
        changes (cls/generate-update-shapes
                 (pcb/empty-changes nil (:id page))
                 #{main-id}
                 (fn [shape] (assoc shape :fills (ths/sample-fills-color :fill-color fill-color)))
                 (:objects page)
                 {})]
    (thf/apply-changes library changes)))

(defn cross-file-component-with-copy
  "A component in a shared library file and a copy of it in the current
   file; the library main then changes its child's fill to `new-fill`, so the
   copy is behind it. The library travels as an auxiliary file. Labels: in
   the library :component1 :main-root :main-child, in the file :copy-root
   :copy-child. Roles: :copy-instance -> :copy-child, :copy-root."
  [new-fill]
  (let [library  (-> (thf/sample-file :library :is-shared true)
                     (tho/add-simple-component
                      :component1 :main-root :main-child
                      :child-params {:fills (ths/sample-fills-color :fill-color labeled-fill)}))
        file     (-> (thf/sample-file :file)
                     (thc/instantiate-component :component1 :copy-root
                                                :library library
                                                :children-labels [:copy-child]))
        library' (change-main-child-fill library new-fill)]
    (-> (tm/make-situation file {:copy-instance :copy-child
                                 :copy-root     :copy-root})
        (tm/with-aux-files {(:id library') library'}))))

(defn cross-file-component-with-many-children
  "A component with three children in a shared library file and a copy of it
   in the current file; the library main then deletes its middle child, so
   the copy is behind it. The library travels as an auxiliary file. Labels:
   in the library :component1 :main-root :main-child1/2/3, in the file
   :copy-root :copy-child1/2/3. Roles: :copy-root and :copy-child-1/2/3."
  []
  (let [library  (-> (thf/sample-file :library :is-shared true)
                     (tho/add-component-with-many-children
                      :component1 :main-root [:main-child1 :main-child2 :main-child3]))
        file     (-> (thf/sample-file :file)
                     (thc/instantiate-component :component1 :copy-root
                                                :library library
                                                :children-labels [:copy-child1
                                                                  :copy-child2
                                                                  :copy-child3]))
        page     (thf/current-page library)
        [_ del]  (cls/generate-delete-shapes (pcb/empty-changes nil (:id page))
                                             (:data library)
                                             page
                                             (:objects page)
                                             #{(thi/id :main-child2)}
                                             {})
        library' (thf/apply-changes library del)]
    (-> (tm/make-situation file {:copy-root    :copy-root
                                 :copy-child-1 :copy-child1
                                 :copy-child-2 :copy-child2
                                 :copy-child-3 :copy-child3})
        (tm/with-aux-files {(:id library') library'}))))

(defn component-nested-in-a-sub-board
  "An `:inner` component (root and rect) and an `:outer` one whose main board
   holds a sub-board with a copy of `:inner` in it, and a copy of `:outer`.
   Labels: :inner-root :inner-rect, :outer-root :sub-board :nested,
   :outer-copy. Roles: :main-instance -> :inner-rect, :main-root ->
   :outer-root, :copy-root -> :outer-copy."
  []
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-simple-component :inner :inner-root :inner-rect
                                           :root-params {:width 50 :height 50}
                                           :child-params {:width 20 :height 20
                                                          :fills (ths/sample-fills-color
                                                                  :fill-color labeled-fill)})
                 (tho/add-frame :outer-root :width 200 :height 200)
                 (tho/add-frame :sub-board :parent-label :outer-root :width 100 :height 100)
                 (thc/instantiate-component :inner :nested :parent-label :sub-board)
                 (thc/make-component :outer :outer-root)
                 (thc/instantiate-component :outer :outer-copy))]
    (tm/make-situation file {:main-instance :inner-rect
                             :main-root     :outer-root
                             :copy-root     :outer-copy})))

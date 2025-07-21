;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.test-helpers.compositions
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.logic.variants :as clv]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.text :as txt]
   [app.common.types.container :as ctn]
   [app.common.types.shape :as cts]))

;; ----- File building

(defn add-rect
  [file rect-label & {:keys [] :as params}]
  ;; Generated shape tree:
  ;; :rect-label [:type :rect :name Rect1]
  (ths/add-sample-shape file rect-label
                        (merge {:type :rect
                                :name "Rect1"}
                               params)))

(defn add-text
  [file text-label content & {:keys [text-params] :as text}]
  (let [shape (-> (cts/setup-shape {:type :text :x 0 :y 0})
                  (txt/change-text content))]
    (ths/add-sample-shape file text-label
                          (merge shape
                                 text-params))))

(defn add-frame
  [file frame-label & {:keys [] :as params}]
  ;; Generated shape tree:
  ;; :frame-label [:type :frame :name Frame1]
  (ths/add-sample-shape file frame-label
                        (merge {:type :frame
                                :name "Frame1"}
                               params)))

(defn add-group
  [file group-label & {:keys [] :as params}]
  ;; Generated shape tree:
  ;; :group-label [:type :group :name Group1]
  (ths/add-sample-shape file group-label
                        (merge {:type :group
                                :name "Group1"}
                               params)))

(defn add-frame-with-child
  [file frame-label child-label & {:keys [frame-params child-params]}]
  ;; Generated shape tree:
  ;; :frame-label [:name Frame1]
  ;;     :child-label [:name Rect1]
  (-> file
      (add-frame frame-label frame-params)
      (ths/add-sample-shape child-label
                            (merge {:type :rect
                                    :name "Rect1"
                                    :parent-label frame-label}
                                   child-params))))

(defn add-frame-with-text
  [file frame-label child-label text & {:keys [frame-params child-params]}]
  (let [shape (-> (cts/setup-shape {:type :text :x 0 :y 0 :grow-type :auto-width})
                  (txt/change-text text)
                  (assoc :position-data nil
                         :parent-label frame-label))]
    (-> file
        (add-frame frame-label frame-params)
        (ths/add-sample-shape child-label
                              (merge shape
                                     child-params)))))

(defn add-minimal-component
  [file component-label root-label
   & {:keys [component-params root-params]}]
  ;; Generated shape tree:
  ;; {:root-label} [:name Frame1]    # [Component :component-label]
  (-> file
      (add-frame root-label root-params)
      (thc/make-component component-label root-label component-params)))

(defn add-minimal-component-with-copy
  [file component-label main-root-label copy-root-label
   & {:keys [component-params main-root-params copy-root-params]}]
  ;; Generated shape tree:
  ;; {:main-root-label} [:name Frame1]    # [Component :component-label]
  ;; :copy-root-label [:name Frame1] #--> [Component :component-label] :main-root-label
  (-> file
      (add-minimal-component component-label
                             main-root-label
                             :component-params component-params
                             :root-params main-root-params)
      (thc/instantiate-component component-label copy-root-label copy-root-params)))

(defn add-simple-component
  [file component-label root-label child-label
   & {:keys [component-params root-params child-params]}]
  ;; Generated shape tree:
  ;; {:root-label} [:name Frame1]    # [Component :component-label]
  ;;     :child-label [:name Rect1]
  (-> file
      (add-frame-with-child root-label child-label :frame-params root-params :child-params child-params)
      (thc/make-component component-label root-label component-params)))

(defn add-simple-component-with-copy
  [file component-label main-root-label main-child-label copy-root-label
   & {:keys [component-params main-root-params main-child-params copy-root-params]}]
  ;; Generated shape tree:
  ;; {:main-root-label} [:name Frame1]       # [Component :component-label]
  ;;     :main-child-label [:name Rect1]
  ;;
  ;; :copy-root-label [:name Frame1]         #--> [Component :component-label] :main-root-label
  ;;     <no-label> [:name Rect1]            ---> :main-child-label
  (-> file
      (add-simple-component component-label
                            main-root-label
                            main-child-label
                            :component-params component-params
                            :root-params main-root-params
                            :child-params main-child-params)
      (thc/instantiate-component component-label copy-root-label copy-root-params)))

(defn add-component-with-many-children
  [file component-label root-label child-labels
   & {:keys [component-params root-params child-params-list]}]
  ;; Generated shape tree:
  ;; {:root-label} [:name Frame1]            # [Component :component-label]
  ;;     :child1-label [:name Rect1]
  ;;     :child2-label [:name Rect2]
  ;;     :child3-label [:name Rect3]
  (as-> file $
    (add-frame $ root-label root-params)
    (reduce (fn [file [index [label params]]]
              (ths/add-sample-shape file
                                    label
                                    (merge {:type :rect
                                            :name (str "Rect" (inc index))
                                            :parent-label root-label}
                                           params)))
            $
            (d/enumerate (d/zip-all child-labels child-params-list)))
    (thc/make-component $ component-label root-label component-params)))

(defn add-component-with-many-children-and-copy
  [file component-label main-root-label main-child-labels copy-root-label
   & {:keys [component-params main-root-params main-child-params-list copy-root-params]}]
  ;; Generated shape tree:
  ;;  {:root-label} [:name Frame1]            # [Component :component-label]
  ;;      :child1-label [:name Rect1]
  ;;      :child2-label [:name Rect2]
  ;;      :child3-label [:name Rect3]
  ;;
  ;;  :copy-root-label [:name Frame1]         #--> [Component :component-label] :root-label
  ;;      <no-label> [:name Rect1]            ---> :child1-label
  ;;      <no-label> [:name Rect2]            ---> :child2-label
  ;;      <no-label> [:name Rect3]            ---> :child3-label
  (-> file
      (add-component-with-many-children component-label
                                        main-root-label
                                        main-child-labels
                                        :component-params component-params
                                        :root-params main-root-params
                                        :child-params-list main-child-params-list)
      (thc/instantiate-component component-label copy-root-label copy-root-params)))

(defn add-nested-component
  [file component1-label main1-root-label main1-child-label component2-label main2-root-label nested-head-label
   & {:keys [component1-params root1-params main1-child-params component2-params main2-root-params nested-head-params]}]
  ;; Generated shape tree:
  ;; {:main1-root-label} [:name Frame1]      # [Component :component1-label]
  ;;     :main1-child-label [:name Rect1]
  ;;
  ;; {:main2-root-label} [:name Frame2]      # [Component :component2-label]
  ;;     :nested-head-label [:name Frame1]   @--> [Component :component1-label] :main1-root-label
  ;;         <no-label> [:name Rect1]        ---> :main1-child-label
  (-> file
      (add-simple-component component1-label
                            main1-root-label
                            main1-child-label
                            :component-params component1-params
                            :root-params root1-params
                            :child-params main1-child-params)
      (add-frame main2-root-label (merge {:name "Frame2"}
                                         main2-root-params))
      (thc/instantiate-component component1-label
                                 nested-head-label
                                 (assoc nested-head-params
                                        :parent-label main2-root-label))
      (thc/make-component component2-label
                          main2-root-label
                          component2-params)))

(defn add-nested-component-with-copy
  [file component1-label main1-root-label main1-child-label component2-label main2-root-label nested-head-label copy2-root-label
   & {:keys [component1-params root1-params main1-child-params component2-params main2-root-params nested-head-params copy2-root-params]}]
  ;; Generated shape tree:
  ;; {:main1-root-label} [:name Frame1]      # [Component :component1-label]
  ;;     :main1-child-label [:name Rect1]
  ;;
  ;; {:main2-root-label} [:name Frame2]      # [Component :component2-label]
  ;;     :nested-head-label [:name Frame1]   @--> [Component :component1-label] :main1-root-label
  ;;         <no-label> [:name Rect1]        ---> :main1-child-label
  ;;
  ;; :copy2-label [:name Frame2]             #--> [Component :component2-label] :main2-root-label
  ;;     <no-label> [:name Frame1]           @--> [Component :component1-label] :nested-head-label
  ;;         <no-label> [:name Rect1]        ---> <no-label>
  (-> file
      (add-nested-component component1-label
                            main1-root-label
                            main1-child-label
                            component2-label
                            main2-root-label
                            nested-head-label
                            :component1-params component1-params
                            :root1-params root1-params
                            :main1-child-params main1-child-params
                            :component2-params component2-params
                            :main2-root-params main2-root-params
                            :nested-head-params nested-head-params)
      (thc/instantiate-component component2-label copy2-root-label copy2-root-params)))

;; ----- Getters

(defn bottom-shape-by-id
  "Get the deepest descendant of a shape by id"
  [file id & {:keys [page-label]}]
  (let [shape (ths/get-shape-by-id file id :page-label page-label)]
    (if (some? (:shapes shape))
      (let [child-id (-> (:shapes shape)
                         first)]
        (bottom-shape-by-id file child-id :page-label page-label))
      shape)))

(defn bottom-shape
  "Get the deepest descendant of a shape by tag"
  [file tag & {:keys [page-label]}]
  (let [shape (ths/get-shape file tag :page-label page-label)]
    (bottom-shape-by-id file (:id shape) :page-label page-label)))

(defn bottom-fill-color
  "Get the first fill color of the deepest descendant of a shape by tag"
  [file tag & {:keys [page-label]}]
  (-> (bottom-shape file tag :page-label page-label)
      :fills
      first
      :fill-color))

;; ----- File modifiers

(defn propagate-component-changes
  "Propagates the component changes for component specified by component-tag"
  [file component-tag]
  (let [file-id (:id file)

        changes (-> (pcb/empty-changes)
                    (cll/generate-sync-file-changes
                     nil
                     :components
                     file-id
                     (:id (thc/get-component  file component-tag))
                     file-id
                     {file-id file}
                     file-id))]
    (thf/apply-changes file changes)))

(defn swap-component
  "Swap the specified shape by the component specified by component-tag"
  [file shape component-tag & {:keys [page-label propagate-fn keep-touched? new-shape-label]}]
  (let [page    (if page-label
                  (thf/get-page file page-label)
                  (thf/current-page file))
        libraries {(:id  file) file}

        orig-shapes (when keep-touched? (cfh/get-children-with-self (:objects page) (:id shape)))

        [new-shape _all-parents changes]
        (cll/generate-component-swap (pcb/empty-changes)
                                     (:objects page)
                                     shape
                                     (:data file)
                                     page
                                     libraries
                                     (->  (thc/get-component file component-tag)
                                          :id)
                                     0
                                     nil
                                     {}
                                     (true? keep-touched?))

        changes (if keep-touched?
                  (clv/generate-keep-touched changes new-shape shape orig-shapes page libraries (:data file))
                  changes)


        file' (thf/apply-changes file changes)]
    (when new-shape-label
      (thi/set-id! new-shape-label (:id new-shape)))
    (if propagate-fn
      (propagate-fn file')
      file')))

(defn swap-component-in-shape [file shape-tag component-tag & {:keys [page-label propagate-fn]}]
  (swap-component file (ths/get-shape file shape-tag :page-label page-label) component-tag :page-label page-label :propagate-fn propagate-fn))

(defn swap-component-in-first-child [file shape-tag component-tag & {:keys [page-label propagate-fn]}]
  (let [first-child-id (->> (ths/get-shape file shape-tag :page-label page-label)
                            :shapes
                            first)]
    (swap-component file
                    (ths/get-shape-by-id file first-child-id :page-label page-label)
                    component-tag
                    :page-label page-label
                    :propagate-fn propagate-fn)))

(defn update-color
  "Update the first fill color for the shape identified by shape-tag"
  [file shape-tag color & {:keys [page-label propagate-fn]}]
  (let [page (if page-label
               (thf/get-page file page-label)
               (thf/current-page file))
        changes
        (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                    #{(:id (ths/get-shape file shape-tag :page-label page-label))}
                                    (fn [shape]
                                      (assoc shape :fills (ths/sample-fills-color :fill-color color)))
                                    (:objects page)
                                    {})
        file' (thf/apply-changes file changes)]
    (if propagate-fn
      (propagate-fn file')
      file')))

(defn update-bottom-color
  "Update the first fill color of the deepest descendant for the shape identified by shape-tag"
  [file shape-tag color & {:keys [page-label propagate-fn]}]
  (let [page (if page-label
               (thf/get-page file page-label)
               (thf/current-page file))
        changes
        (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                    #{(:id (bottom-shape file shape-tag :page-label page-label))}
                                    (fn [shape]
                                      (assoc shape :fills (ths/sample-fills-color :fill-color color)))
                                    (:objects page)
                                    {})
        file' (thf/apply-changes file changes)]
    (if propagate-fn
      (propagate-fn file')
      file')))

(defn reset-overrides [file shape & {:keys [page-label propagate-fn]}]
  (let [page (if page-label
               (thf/get-page file page-label)
               (thf/current-page file))
        container (ctn/make-container page :page)
        file-id   (:id file)
        changes   (-> (pcb/empty-changes)
                      (cll/generate-reset-component
                       file
                       {file-id file}
                       (ctn/make-container container :page)
                       (:id shape)))
        file' (thf/apply-changes file changes)]
    (if propagate-fn
      (propagate-fn file')
      file')))

(defn reset-overrides-in-first-child [file shape-tag & {:keys [page-label propagate-fn]}]
  (let [first-child-id (->>
                        (ths/get-shape file shape-tag :page-label page-label)
                        :shapes
                        first)
        shape (ths/get-shape-by-id file first-child-id :page-label page-label)]
    (reset-overrides file shape :page-label page-label :propagate-fn propagate-fn)))

(defn delete-shape [file shape-tag & {:keys [page-label propagate-fn]}]
  (let [page (if page-label
               (thf/get-page file page-label)
               (thf/current-page file))
        [_ changes] (cls/generate-delete-shapes (pcb/empty-changes nil (:id page))
                                                file
                                                page
                                                (:objects page)
                                                #{(-> (ths/get-shape file shape-tag :page-label page-label)
                                                      :id)}
                                                {})
        file' (thf/apply-changes file changes)]
    (if propagate-fn
      (propagate-fn file')
      file')))

(defn duplicate-shape [file shape-tag & {:keys [page-label propagate-fn]}]
  (let [page (if page-label
               (thf/get-page file page-label)
               (thf/current-page file))
        shape (ths/get-shape file shape-tag :page-label page-label)
        changes
        (-> (pcb/empty-changes nil)
            (cll/generate-duplicate-changes (:objects page)         ;; objects
                                            page                    ;; page
                                            #{(:id shape)}          ;; ids
                                            (gpt/point 0 0)         ;; delta
                                            {(:id  file) file}      ;; libraries
                                            (:data file)            ;; library-data
                                            (:id file))             ;; file-id
            (cll/generate-duplicate-changes-update-indices (:objects page)  ;; objects
                                                           #{(:id shape)}))
        file' (thf/apply-changes file changes)]
    (if propagate-fn
      (propagate-fn file')
      file')))


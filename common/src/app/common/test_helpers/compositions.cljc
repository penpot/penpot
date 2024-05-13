;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.test-helpers.compositions
  (:require
   [app.common.data :as d]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.shapes :as ths]))

(defn add-rect
  [file rect-label & {:keys [] :as params}]
  ;; Generated shape tree:
  ;; :rect-label [:type :rect :name: Rect1]
  (ths/add-sample-shape file rect-label
                        (merge {:type :rect
                                :name "Rect1"}
                               params)))

(defn add-frame
  [file frame-label & {:keys [] :as params}]
  ;; Generated shape tree:
  ;; :frame-label [:type :frame :name: Frame1]
  (ths/add-sample-shape file frame-label
                        (merge {:type :frame
                                :name "Frame1"}
                               params)))

(defn add-frame-with-child
  [file frame-label child-label & {:keys [frame-params child-params]}]
  ;; Generated shape tree:
  ;; :frame-label [:name: Frame1]
  ;;     :child-label [:name: Rect1]
  (-> file
      (add-frame frame-label frame-params)
      (ths/add-sample-shape child-label
                            (merge {:type :rect
                                    :name "Rect1"
                                    :parent-label frame-label}
                                   child-params))))

(defn add-simple-component
  [file component-label root-label child-label
   & {:keys [component-params root-params child-params]}]
  ;; Generated shape tree:
  ;; {:root-label} [:name: Frame1]    # [Component :component-label]
  ;;     :child-label [:name: Rect1]  
  (-> file
      (add-frame-with-child root-label child-label :frame-params root-params :child-params child-params)
      (thc/make-component component-label root-label component-params)))

(defn add-simple-component-with-copy
  [file component-label main-root-label main-child-label copy-root-label
   & {:keys [component-params main-root-params main-child-params copy-root-params]}]
  ;; Generated shape tree:
  ;; {:main-root-label} [:name: Frame1]       # [Component :component-label]
  ;;     :main-child-label [:name: Rect1]     
  ;;
  ;; :copy-root-label [:name: Frame1]         #--> [Component :component-label] :main-root-label
  ;;     <no-label> [:name: Rect1]            ---> :main-child-label
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
  ;; {:root-label} [:name: Frame1]            # [Component :component-label]
  ;;     :child1-label [:name: Rect1]         
  ;;     :child2-label [:name: Rect2]         
  ;;     :child3-label [:name: Rect3]         
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
  ;;  {:root-label} [:name: Frame1]            # [Component :component-label]
  ;;      :child1-label [:name: Rect1]         
  ;;      :child2-label [:name: Rect2]         
  ;;      :child3-label [:name: Rect3]         
  ;;
  ;;  :copy-root-label [:name: Frame1]         #--> [Component :component-label] :root-label
  ;;      <no-label> [:name: Rect1]            ---> :child1-label
  ;;      <no-label> [:name: Rect2]            ---> :child2-label
  ;;      <no-label> [:name: Rect3]            ---> :child3-label
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
  ;; {:main1-root-label} [:name: Frame1]      # [Component :component1-label]
  ;;     :main1-child-label [:name: Rect1]    
  ;;
  ;; {:main2-root-label} [:name: Frame2]      # [Component :component2-label]
  ;;     :nested-head-label [:name: Frame1]   @--> [Component :component1-label] :main1-root-label
  ;;         <no-label> [:name: Rect1]        ---> :main1-child-label
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
  ;; {:main1-root-label} [:name: Frame1]      # [Component :component1-label]
  ;;     :main1-child-label [:name: Rect1]    
  ;;
  ;; {:main2-root-label} [:name: Frame2]      # [Component :component2-label]
  ;;     :nested-head-label [:name: Frame1]   @--> [Component :component1-label] :main1-root-label
  ;;         <no-label> [:name: Rect1]        ---> :main1-child-label
  ;;
  ;; :copy2-label [:name: Frame2]             #--> [Component :component2-label] :main2-root-label
  ;;     <no-label> [:name: Frame1]           @--> [Component :component1-label] :nested-head-label
  ;;         <no-label> [:name: Rect1]        ---> <no-label>
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
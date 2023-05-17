;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.component)

;; Attributes that may be synced in components, and the group they belong to.
;; When one attribute is modified in a shape inside a component, the corresponding
;; group is marked as :touched. Then, if the shape is synced with the remote shape
;; in the main component, none of the attributes of the same group is changed.

(def sync-attrs
  {:name                    :name-group
   :fills                   :fill-group
   ;; FIXME: this should be deleted?
   :fill-color              :fill-group
   :fill-opacity            :fill-group
   :fill-color-gradient     :fill-group
   :fill-color-ref-file     :fill-group
   :fill-color-ref-id       :fill-group
   :hide-fill-on-export     :fill-group
   :content                 :content-group
   :position-data           :content-group
   :hidden                  :visibility-group
   :blocked                 :modifiable-group
   :grow-type               :text-font-group
   :font-family             :text-font-group
   :font-size               :text-font-group
   :font-style              :text-font-group
   :font-weight             :text-font-group
   :letter-spacing          :text-display-group
   :line-height             :text-display-group
   :text-align              :text-display-group
   :strokes                 :stroke-group
   :stroke-color            :stroke-group
   :stroke-color-gradient   :stroke-group
   :stroke-color-ref-file   :stroke-group
   :stroke-color-ref-id     :stroke-group
   :stroke-opacity          :stroke-group
   :stroke-style            :stroke-group
   :stroke-width            :stroke-group
   :stroke-alignment        :stroke-group
   :stroke-cap-start        :stroke-group
   :stroke-cap-end          :stroke-group
   :rx                      :radius-group
   :ry                      :radius-group
   :r1                      :radius-group
   :r2                      :radius-group
   :r3                      :radius-group
   :r4                      :radius-group
   :type                    :geometry-group
   :selrect                 :geometry-group
   :points                  :geometry-group
   :locked                  :geometry-group
   :proportion              :geometry-group
   :proportion-lock         :geometry-group
   :x                       :geometry-group
   :y                       :geometry-group
   :width                   :geometry-group
   :height                  :geometry-group
   :rotation                :geometry-group
   :transform               :geometry-group
   :transform-inverse       :geometry-group
   :opacity                 :layer-effects-group
   :blend-mode              :layer-effects-group
   :shadow                  :shadow-group
   :blur                    :blur-group
   :masked-group?           :mask-group
   :constraints-h           :constraints-group
   :constraints-v           :constraints-group
   :fixed-scroll            :constraints-group
   :exports                 :exports-group

   :layout                  :layout-container
   :layout-align-content    :layout-container
   :layout-align-items      :layout-container
   :layout-flex-dir         :layout-container
   :layout-gap              :layout-container
   :layout-gap-type         :layout-container
   :layout-justify-content  :layout-container
   :layout-wrap-type        :layout-container
   :layout-padding-type     :layout-container
   :layout-padding          :layout-container
   :layout-h-orientation    :layout-container
   :layout-v-orientation    :layout-container

   :layout-item-margin      :layout-item
   :layout-item-margin-type :layout-item
   :layout-item-h-sizing    :layout-item
   :layout-item-v-sizing    :layout-item
   :layout-item-max-h       :layout-item
   :layout-item-min-h       :layout-item
   :layout-item-max-w       :layout-item
   :layout-item-min-w       :layout-item
   :layout-item-align-self  :layout-item})


(defn instance-root?
  "Check if this shape is the head of a top instance."
  [shape]
  (some? (:component-root? shape)))

(defn instance-head?
  "Check if this shape is the head of a top instance or a subinstance."
  [shape]
  (some? (:component-id shape)))

(defn instance-of?
  [shape file-id component-id]
  (and (some? (:component-id shape))
       (some? (:component-file shape))
       (= (:component-id shape) component-id)
       (= (:component-file shape) file-id)))

(defn is-main-of?
  [shape-main shape-inst]
  (and (:shape-ref shape-inst)
       (or (= (:shape-ref shape-inst) (:id shape-main))
           (= (:shape-ref shape-inst) (:shape-ref shape-main)))))

(defn main-instance?
  "Check if this shape is the root of the main instance of some component."
  [shape]
  (some? (:main-instance? shape)))

(defn is-main-instance?
  "Check if this shape is the root of the main instance of the given component."
  [shape-id page-id component]
  (and (= shape-id (:main-instance-id component))
       (= page-id (:main-instance-page component))))

(defn get-component-root
  [component]
  (if (some? (:main-instance-id component))
    (get-in component [:objects (:main-instance-id component)])
    (get-in component [:objects (:id component)])))

(defn uses-library-components?
  "Check if the shape uses any component in the given library."
  [shape library-id]
  (and (some? (:component-id shape))
       (= (:component-file shape) library-id)))

(defn in-component-copy?
  "Check if the shape is inside a component non-main instance."
  [shape]
  (some? (:shape-ref shape)))

(defn in-component-copy-not-root?
  "Check if the shape is inside a component non-main instance and
  is not the root shape."
  [shape]
  (and (some? (:shape-ref shape))
       (nil? (:component-id shape))))

(defn detach-shape
  "Remove the links and leave it as a plain shape, detached from any component."
  [shape]
  (dissoc shape
          :component-id
          :component-file
          :component-root?
          :remote-synced?
          :shape-ref
          :touched))

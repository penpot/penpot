;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.component
  (:require
   [app.common.data :as d]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

;; Attributes that may be synced in components, and the group they belong to.
;; When one attribute is modified in a shape inside a component, the corresponding
;; group is marked as :touched. Then, if the shape is synced with the remote shape
;; in the main component, none of the attributes of the same group is changed.

(def sync-attrs
  {:name                    :name-group
   :fills                   :fill-group
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

   ;; DEPRECATED: FIXME: this attrs are deprecated for a long time but
   ;; we still have tests that uses this attribute for synchronization
   :stroke-width            :stroke-group
   :fill-color              :fill-group
   :fill-opacity            :fill-group

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
   :masked-group            :mask-group
   :constraints-h           :constraints-group
   :constraints-v           :constraints-group
   :fixed-scroll            :constraints-group
   :bool-type               :bool-group
   :bool-content            :bool-group
   :exports                 :exports-group
   :grids                   :grids-group

   :layout                  :layout-container

   :layout-align-content    :layout-align-content
   :layout-align-items      :layout-align-items
   :layout-flex-dir         :layout-flex-dir
   :layout-gap              :layout-gap
   :layout-gap-type         :layout-gap
   :layout-justify-content  :layout-justify-content
   :layout-justify-items    :layout-justify-items
   :layout-wrap-type        :layout-wrap-type
   :layout-padding-type     :layout-padding
   :layout-padding          :layout-padding

   :layout-grid-dir         :layout-grid-dir
   :layout-grid-rows        :layout-grid-rows
   :layout-grid-columns     :layout-grid-columns
   :layout-grid-cells       :layout-grid-cells

   :layout-item-margin      :layout-item-margin
   :layout-item-margin-type :layout-item-margin
   :layout-item-h-sizing    :layout-item-h-sizing
   :layout-item-v-sizing    :layout-item-v-sizing
   :layout-item-max-h       :layout-item-max-h
   :layout-item-min-h       :layout-item-min-h
   :layout-item-max-w       :layout-item-max-w
   :layout-item-min-w       :layout-item-min-w
   :layout-item-absolute    :layout-item-absolute
   :layout-item-z-index     :layout-item-z-index
   :layout-item-align-self  :layout-item-align-self})

(def swap-keep-attrs
  #{:layout-item-margin
    :layout-item-margin-type
    :layout-item-h-sizing
    :layout-item-v-sizing
    :layout-item-max-h
    :layout-item-min-h
    :layout-item-max-w
    :layout-item-min-w
    :layout-item-absolute
    :layout-item-z-index
    :layout-item-align-self})

(defn instance-root?
  "Check if this shape is the head of a top instance."
  [shape]
  (true? (:component-root shape)))

(defn instance-head?
  "Check if this shape is the head of a top instance or a subinstance."
  [shape]
  (some? (:component-id shape)))

(defn subinstance-head?
  "Check if this shape is the head of a subinstance."
  [shape]
  (and (some? (:component-id shape))
       (nil? (:component-root shape))))

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
  "Check if this shape is the root of the main instance of some
  component."
  [shape]
  (true? (:main-instance shape)))

(defn in-component-copy?
  "Check if the shape is inside a component non-main instance."
  [shape]
  (some? (:shape-ref shape)))

(defn in-component-copy-not-head?
  "Check if the shape is inside a component non-main instance and
  is not the head of a subinstance."
  [shape]
  (and (some? (:shape-ref shape))
       (nil? (:component-id shape))))

(defn in-component-copy-not-root?
  "Check if the shape is inside a component non-main instance and
  is not the root shape."
  [shape]
  (and (some? (:shape-ref shape))
       (nil? (:component-root shape))))

(defn main-instance-of?
  "Check if this shape is the root of the main instance of the given component."
  [shape-id page-id component]
  (and (= shape-id (:main-instance-id component))
       (= page-id (:main-instance-page component))))

(defn build-swap-slot-group
  "Convert a swap-slot into a :touched group"
  [swap-slot]
  (when swap-slot
    (keyword (str "swap-slot-" swap-slot))))

(defn get-swap-slot
  "If the shape has a :touched group in the form :swap-slot-<uuid>, get the id."
  [shape]
  (let [group (->> (:touched shape)
                   (map name)
                   (d/seek #(str/starts-with? % "swap-slot-")))]
    (when group
      (uuid/uuid (subs group 10)))))

(defn match-swap-slot?
  [shape-inst shape-main]
  (let [slot-inst   (get-swap-slot shape-inst)
        slot-main   (get-swap-slot shape-main)]
    (when (some? slot-inst)
      (or (= slot-inst slot-main)
          (= slot-inst (:id shape-main))))))

(defn get-component-root
  [component]
  (if (true? (:main-instance-id component))
    (get-in component [:objects (:main-instance-id component)])
    (get-in component [:objects (:id component)])))

(defn uses-library-components?
  "Check if the shape uses any component in the given library."
  [shape library-id]
  (and (some? (:component-id shape))
       (= (:component-file shape) library-id)))

(defn detach-shape
  "Remove the links and leave it as a plain shape, detached from any component."
  [shape]
  (dissoc shape
          :component-id
          :component-file
          :component-root
          :main-instance
          :remote-synced
          :shape-ref
          :touched))


(defn- extract-ids [shape]
  (if (map? shape)
    (let [current-id (:id shape)
          child-ids  (mapcat extract-ids (:children shape))]
      (cons current-id child-ids))
    []))

(defn diff-components
  "Compare two components, and return a set of the keys with different values"
  [comp1 comp2]
  (let [eq (fn [key val1 val2]
             (if (= key :objects)
               (= (extract-ids val1) (extract-ids val2))
               (= val1 val2)))]
    (->> (concat (keys comp1) (keys comp2))
         (distinct)
         (filter #(not (eq % (get comp1 %) (get comp2 %))))
         set)))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.component
  (:require
   [app.common.data :as d]
   [app.common.schema :as sm]
   [app.common.types.page :as ctp]
   [app.common.types.plugins :as ctpg]
   [app.common.types.variant :as ctv]
   [cuerdas.core :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def schema:component
  [:merge
   [:map
    [:id ::sm/uuid]
    [:name :string]
    [:path {:optional true} [:maybe :string]]
    [:modified-at {:optional true} ::sm/inst]
    [:objects {:gen/max 10 :optional true} ::ctp/objects]
    [:main-instance-id ::sm/uuid]
    [:main-instance-page ::sm/uuid]
    [:plugin-data {:optional true} ::ctpg/plugin-data]]
   ::ctv/variant-component])

(sm/register! ::component schema:component)

(def check-component
  (sm/check-fn schema:component))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INIT & HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
   :bool-type               :content-group
   :bool-content            :content-group
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

(defn component-attr?
  "Check if some attribute is one that is involved in component syncrhonization.
   Note that design tokens also are involved, although they go by an alternate
   route and thus they are not part of :sync-attrs."
  [attr]
  (or (get sync-attrs attr)
      (= :applied-tokens attr)))

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

(defn subcopy-head?
  "Check if this shape is the head of a subinstance that is a copy."
  [shape]
  ;; This is redundant with the previous one, but may give more security
  ;; in case of bugs.
  (and (some? (:component-id shape))
       (nil? (:component-root shape))
       (some? (:shape-ref shape))))

(defn instance-of?
  [shape file-id component-id]
  (and (some? (:component-id shape))
       (some? (:component-file shape))
       (= (:component-id shape) component-id)
       (= (:component-file shape) file-id)))

(defn is-main-of?
  [shape-main shape-inst]
  (= (:shape-ref shape-inst) (:id shape-main)))

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


(defn is-variant?
  "Check if this shape or component is a variant component"
  [item]
  (some? (:variant-id item)))


(defn is-variant-container?
  "Check if this shape is a variant container"
  [shape]
  (:is-variant-container shape))


(defn set-touched-group
  [touched group]
  (when group
    (conj (or touched #{}) group)))

(defn touched-group?
  [shape group]
  ((or (:touched shape) #{}) group))

(defn build-swap-slot-group
  "Convert a swap-slot into a :touched group"
  [swap-slot]
  (when swap-slot
    (keyword (str "swap-slot-" swap-slot))))

(defn swap-slot?
  [group]
  (str/starts-with? (name group) "swap-slot-"))

(defn normal-touched-groups
  "Gets all touched groups that are not swap slots."
  [shape]
  (into #{} (remove swap-slot? (:touched shape))))

(defn group->swap-slot
  [group]
  (parse-uuid (subs (name group) 10)))

(defn get-swap-slot
  "If the shape has a :touched group in the form :swap-slot-<uuid>, get the id."
  [shape]
  (let [group (d/seek swap-slot? (:touched shape))]
    (when group
      (group->swap-slot group))))

(defn set-swap-slot
  "Add a touched group with a form :swap-slot-<uuid>."
  [shape swap-slot]
  (cond-> shape
    (some? swap-slot)
    (update :touched set-touched-group (build-swap-slot-group swap-slot))))

(defn match-swap-slot?
  [shape-main shape-inst]
  (let [slot-main   (get-swap-slot shape-main)
        slot-inst   (get-swap-slot shape-inst)]
    (when (some? slot-inst)
      (or (= slot-main slot-inst)
          (= (:id shape-main) slot-inst)))))

(defn remove-swap-slot
  [shape]
  (update shape :touched
          (fn [touched]
            (into #{} (remove #(str/starts-with? (name %) "swap-slot-") touched)))))

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

(defn allow-duplicate?
  [objects shape]

  (let [parent (get objects (:parent-id shape))]
    ;; We don't want to change the structure of component copies
    (and (not (in-component-copy-not-head? shape))
         ;; Non instance, non copy. We allow
         (or (not (instance-head? shape))
             (not (in-component-copy? parent))))))

(defn all-touched-groups
  []
  (into #{} (vals sync-attrs)))

(defn valid-touched-group?
  [group]
  (try
    (or (contains? (all-touched-groups) group)
        (and (swap-slot? group)
             (some? (group->swap-slot group))))
    (catch #?(:clj Throwable :cljs :default) _
      false)))

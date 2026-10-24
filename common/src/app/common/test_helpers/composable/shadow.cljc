;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.test-helpers.composable.shadow
  "Authored overrides, kept independently of production touched writes.
   Each shape id maps groups to the attribute values the user authored."
  (:require
   [app.common.math :as mth]
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.texts :as thtx]))

;; This table is a specification, deliberately not ctk/sync-attrs.
(def groups
  {:fills :fill-group :opacity :layer-effects-group :name :name-group})

(def attributes
  {:fill-group [:fills :hide-fill-on-export]
   :layer-effects-group [:opacity :blend-mode]
   :name-group [:name]
   :geometry-group [:width :height :rotation :flip-x :flip-y :content-scale]
   :text-font-group [:grow-type]
   :constraints-group [:constraints-h :constraints-v :fixed-scroll]
   :layout-container [:layout]
   :layout-grid-dir [:layout-grid-dir]
   :layout-grid-rows [:layout-grid-rows]
   :layout-grid-columns [:layout-grid-columns]
   :layout-align-content [:layout-align-content]
   :layout-justify-items [:layout-justify-items]
   :layout-wrap-type [:layout-wrap-type]
   :layout-gap [:layout-gap :layout-gap-type]
   :layout-padding [:layout-padding :layout-padding-type]
   :layout-flex-dir [:layout-flex-dir]
   :layout-align-items [:layout-align-items]
   :layout-justify-content [:layout-justify-content]
   :layout-item-margin [:layout-item-margin :layout-item-margin-type]
   :layout-item-max-h [:layout-item-max-h]
   :layout-item-min-h [:layout-item-min-h]
   :layout-item-max-w [:layout-item-max-w]
   :layout-item-min-w [:layout-item-min-w]
   :layout-item-h-sizing [:layout-item-h-sizing]
   :layout-item-v-sizing [:layout-item-v-sizing]
   :layout-item-absolute [:layout-item-absolute]
   :layout-item-z-index [:layout-item-z-index]
   :layout-item-align-self [:layout-item-align-self]})

(def text-groups
  {:text-content-text :letters :text-content-family :family :text-content-weight :weight
   :text-content-size :size :text-content-line-height :line-height :text-content-spacing :spacing
   :text-content-decoration :decoration :text-content-case :case :text-content-fills :fills
   :text-content-typography :typography :text-content-align :align :text-content-direction :direction
   :text-content-valign :valign})

(defn values
  [shape group]
  (if (contains? text-groups group)
    {:content (:content shape)}
    (into {} (map #(vector % (get shape %))) (get attributes group))))

(defn same-value?
  [shape group expected]
  (if-let [unit (get text-groups group)]
    (not (contains? (thtx/override-units (:content expected) (:content shape)) unit))
    (if (= group :geometry-group)
      (every? (fn [[key value]]
                (let [actual (get shape key)]
                  (cond
                    (#{:flip-x :flip-y} key) (= (boolean value) (boolean actual))
                    (and (number? value) (number? actual)) (mth/close? value actual 1e-10)
                    :else (= value actual)))) expected)
      (= expected (values shape group)))))

(defn edit-text
  [model before content]
  (if (:shape-ref before)
    (let [units (thtx/override-units (:content before) content)]
      (reduce (fn [model [group unit]]
                (cond-> model
                  (contains? units unit)
                  (assoc-in [(:id before) group] {:content content})))
              (cond-> model (seq units) (assoc-in [(:id before) :content-group] {}))
              text-groups))
    model))

(defn duplicate
  [model before source-id after copy-id]
  (letfn [(copy-groups [result source-id copy-id]
            (let [groups (get model source-id)
                  result (cond-> result (seq groups) (assoc copy-id groups))]
              (reduce (fn [result [source child]] (copy-groups result source child)) result
                      (map vector (:shapes (get before source-id)) (:shapes (get after copy-id))))))]
    (copy-groups model source-id copy-id)))

(defn edit
  [model before property value]
  (let [after (n/set-property before property value)
        group (get groups property)]
    (if (and (:shape-ref before) (not= (get before property) (get after property)))
      (assoc-in model [(:id before) group] (values after group))
      model)))

(defn clear-subtree
  [model objects root-id]
  (letfn [(clear [model id]
            (reduce clear (dissoc model id) (:shapes (get objects id))))]
    (clear model root-id)))

(defn- in-orientation
  [values source target]
  (reduce (fn [values key]
            (cond-> values
              (not= (boolean (get source key)) (boolean (get target key)))
              (update key not)))
          (update values :rotation
                  #(mod (+ (or % 0) (- (or (:rotation target) 0) (or (:rotation source) 0))) 360))
          [:flip-x :flip-y]))

(defn detach
  "A detached outer head loses its groups. Surviving nested copies inherit
   the authored groups of the near-main links the detach skips."
  [model before after root-id]
  (letfn [(inherit [shape]
            (let [original shape
                  new-ref (:shape-ref (get after (:id shape)))
                  swapped? (contains? (get model (:id shape)) :swap-group)]
              (loop [shape shape groups (get model (:id shape)) seen #{}]
                (let [ref (:shape-ref shape)
                      source (get before ref)
                      inherited (get model ref)
                      inherited (cond-> inherited
                                  (:geometry-group inherited)
                                  (update :geometry-group in-orientation source original))]
                  (if (or (nil? ref) (contains? seen ref) (= ref new-ref))
                    groups
                    (recur source
                           (merge (if swapped?
                                    (select-keys inherited
                                                 (filter #(or (#{:geometry-group :constraints-group} %)
                                                              (.startsWith (name %) "layout-item-"))
                                                         (keys inherited)))
                                    inherited) groups)
                           (conj seen ref)))))))
          (visit [result id]
            (let [shape (get after id)
                  groups (when (:shape-ref shape)
                           (cond
                             (= (:shape-ref shape) (:shape-ref (get before id)))
                             (get model id)
                             (and (contains? (get model id) :swap-group)
                                  (= (:component-id shape)
                                     (:component-id (get after (:shape-ref shape)))))
                             ;; Leaving the enclosing copy makes this a plain
                             ;; copy of its own main, without position inheritance.
                             (dissoc (get model id) :swap-group)
                             :else (inherit (get before id))))
                  result (cond-> (dissoc result id) (seq groups) (assoc id groups))]
              (reduce visit result (:shapes (get before id)))))]
    (visit model root-id)))

(defn update-main
  "Pushing overrides into a nested copy in a main makes them authored at
   that level. Ordinary main shapes do not have touched groups."
  [model before after root-id]
  (letfn [(content-overrides [shape reference]
            (when reference
              (let [content-groups (remove #(or (#{:geometry-group :constraints-group} %)
                                                (.startsWith (name %) "layout-item-"))
                                           (keys attributes))
                    groups (into {}
                                 (keep (fn [group]
                                         (let [expected (values reference group)]
                                           (when-not (same-value? shape group expected)
                                             [group (values shape group)]))))
                                 content-groups)
                    units (when (= :text (:type shape))
                            (thtx/override-units (:content reference) (:content shape)))]
                (reduce (fn [groups [group unit]]
                          (cond-> groups
                            (contains? units unit)
                            (assoc group {:content (:content shape)})))
                        (cond-> groups (seq units) (assoc :content-group {}))
                        text-groups))))
          (push [result id content?]
            (let [shape (get before id)
                  updated (get after id)
                  ref (get after (:shape-ref updated))
                  groups (get model id)
                  content-main (get before (:shape-ref ref))
                  pushed-swap? (and content-main (contains? groups :swap-group)
                                    (= (:component-id ref) (:component-id content-main)))
                  content? (or content? pushed-swap?)
                  groups (merge (when (and content?
                                           (= (:component-id ref) (:component-id content-main)))
                                  (content-overrides shape content-main))
                                groups)
                  root (get before root-id)
                  main-root (get before (:shape-ref root))
                  geometry (cond-> (values shape :geometry-group)
                             (get-in model [(:id main-root) :geometry-group])
                             (in-orientation root main-root))
                  expected (when pushed-swap?
                             (in-orientation (values content-main :geometry-group)
                                             (get before (:parent-id content-main))
                                             (get after (:parent-id ref))))
                  groups (cond-> groups
                           (and pushed-swap?
                                (not (same-value? geometry :geometry-group
                                                  (select-keys expected [:rotation :flip-x :flip-y]))))
                           (assoc :geometry-group geometry))
                  result (cond-> (dissoc result id)
                           (and (contains? groups :swap-group)
                                (not= (:component-id updated) (:component-id ref)))
                           (assoc id {:swap-group {}})
                           (and (:shape-ref ref) (seq groups))
                           (update (:id ref) merge
                                   (cond-> groups
                                     (= (:component-id ref)
                                        (:component-id (get after (:shape-ref ref))))
                                     (dissoc :swap-group))))]
              (reduce #(push %1 %2 content?) result (:shapes shape))))]
    (push model root-id false)))

(defn parent-orientation
  "Authored child geometry moves with its parent's orientation. Compare the
   parent frames, without reading the child's resulting geometry."
  [model before after]
  (reduce-kv
   (fn [result id groups]
     (let [old (get before id)
           shape (get after id)
           parent (get before (:parent-id old))
           new-parent (get after (:parent-id shape))]
       (if (and (:geometry-group groups) old shape
                (= (:parent-id old) (:parent-id shape)) parent new-parent)
         (update-in result [id :geometry-group] in-orientation parent new-parent)
         result)))
   model model))

(defn settle
  "A detached position turns its copies into ordinary shapes. Their former
   component choice stops applying; other authored groups stay in place."
  [model objects]
  (reduce-kv
   (fn [result id groups]
     (let [shape (get objects id)
           position (get objects (:shape-ref shape))
           ordinary? (and shape position
                          (nil? (:component-id shape))
                          (nil? (:component-id position)))
           groups (cond-> groups ordinary? (dissoc :swap-group))]
       (if (seq groups) (assoc result id groups) (dissoc result id))))
   model model))

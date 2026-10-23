;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.test-helpers.composable.shadow
  "Authored overrides, kept independently of production touched writes.
   Each shape id maps groups to the attribute values the user authored."
  (:require
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
   :constraints-group [:constraints-h :constraints-v :fixed-scroll]
   :layout-container [:layout]
   :layout-gap [:layout-gap :layout-gap-type]
   :layout-padding [:layout-padding :layout-padding-type]
   :layout-flex-dir [:layout-flex-dir]
   :layout-align-items [:layout-align-items]
   :layout-justify-content [:layout-justify-content]
   :layout-item-margin [:layout-item-margin :layout-item-margin-type]
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
    (= expected (values shape group))))

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

(defn detach
  "A detached outer head loses its groups. Surviving nested copies inherit
   the authored groups of the near-main links the detach skips."
  [model before after root-id]
  (letfn [(inherit [shape]
            (loop [shape shape groups (get model (:id shape)) seen #{}]
              (let [ref (:shape-ref shape)]
                (if (or (nil? ref) (contains? seen ref)
                        (= ref (:shape-ref (get after (:id shape)))))
                  groups
                  (recur (get before ref) (merge (get model ref) groups)
                         (conj seen ref))))))
          (visit [result id]
            (let [shape (get after id)
                  groups (when (:shape-ref shape) (inherit (get before id)))
                  result (cond-> (dissoc result id) (seq groups) (assoc id groups))]
              (reduce visit result (:shapes (get before id)))))]
    (visit model root-id)))

(defn update-main
  "Pushing overrides into a nested copy in a main makes them authored at
   that level. Ordinary main shapes do not have touched groups."
  [model objects root-id]
  (letfn [(push [result id]
            (let [shape (get objects id)
                  ref (get objects (:shape-ref shape))
                  groups (get model id)
                  result (cond-> (dissoc result id)
                           (and (:shape-ref ref) (seq groups))
                           (update (:id ref) merge groups))]
              (reduce push result (:shapes shape))))]
    (push model root-id)))

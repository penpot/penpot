(ns app.main.ui.workspace.tokens.update
  (:require
   [app.common.types.token :as ctt]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.refs :as refs]
   [app.main.ui.workspace.tokens.changes :as wtch]
   [app.main.ui.workspace.tokens.style-dictionary :as wtsd]
   [clojure.set :as set]))

(defn invert-collect-key-vals
  [xs resolved-tokens]
  (-> (reduce
       (fn [acc [k v]]
         (let [resolved-value (get-in resolved-tokens [v :resolved-value])]
           (update acc resolved-value (fnil conj #{}) k)))
       {} xs)))

(defn shape-ids-by-values
  [attrs-values-map object-id]
  (->> (map (fn [[value attrs]] [attrs {value #{object-id}}]) attrs-values-map)
       (into {})))

(defn deep-merge
  "Like d/deep-merge but unions set values."
  ([a b]
   (cond
     (map? a) (merge-with deep-merge a b)
     (set? a) (set/union a b)
     :else b))
  ([a b & rest]
   (reduce deep-merge a (cons b rest))))

(defn update-workspace-tokens []
  (let [resolved-tokens (wtsd/get-cached-tokens @refs/workspace-tokens)]
    (->> @refs/workspace-page-objects
         (reduce
          (fn [acc [object-id {:keys [applied-tokens]}]]
            (if (seq applied-tokens)
              (let [applied-tokens (-> (invert-collect-key-vals applied-tokens resolved-tokens)
                                       (shape-ids-by-values object-id))]
                (deep-merge acc applied-tokens))
              acc))
          {}))))

(def attributes->shape-update
  {#{:rx :ry} wtch/update-shape-radius-single-corner
   #{:r1 :r2 :r3 :r4} wtch/update-shape-radius-single-corner
   ctt/stroke-width-keys wtch/update-stroke-width
   ctt/sizing-keys wtch/update-shape-dimensions
   ctt/opacity-keys wtch/update-opacity
   #{:p1 :p2 :p3 :p4} (fn [resolved-value shape-ids attrs]
                        (dwsl/update-layout shape-ids {:layout-padding (zipmap attrs (repeat resolved-value))}))
   #{:column-gap :row-gap} wtch/update-layout-spacing
   #{:width :height} wtch/update-shape-dimensions
   #{:layout-item-min-w :layout-item-min-h :layout-item-max-w :layout-item-max-h} wtch/update-layout-sizing-limits
   ctt/rotation-keys wtch/update-rotation})

(merge {} (into {} [[:a 1]]))


(def attributes-collect-by-pairs
  (reduce
   (fn [acc [ks _]]
     (into acc (map (fn [k] [k ks]) ks)))
   {} attributes->shape-update))

(comment
  (update-workspace-tokens)

  nil)

(ns uxbox.util
  (:refer-clojure :exclude [derive])
  (:require [rum.core :as rum]
            [cats.labs.lens :as l]
            [goog.dom.forms :as gforms]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sugar for define rum components
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn component
  [spec]
  (let [name (or (:name spec)
                 (str (gensym "rum-")))
        mixins (or (:mixins spec)
                   [])
        spec (merge (dissoc spec :name :mixins)
                    (when-let [rfn (:render spec)]
                      {:render (fn [state]
                                 [(apply rfn state (:rum/props state)) state])}))
        cls (rum/build-class (conj mixins spec) name)
        ctr (fn self
              ([] (self {}))
              ([&props]
               (let [state {:rum/props props}]
                 (rum/element cls state nil))))]
    (with-meta ctr {:rum/class cls})))

(defn ref-value
  [own ref]
  (let [component (-> own :rum/react-component)
        ref-node (aget (.-refs component) ref)
        dom-node  (.findDOMNode js/ReactDOM ref-node)]
    (.-value dom-node)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Cursored & Lenses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- cursored-key
  [state]
  (str ":rum/cursored-" (:rum/id state)))

(def ^:private
  deref-map-xform
  (map (fn [[k v]] [k (if (satisfies? IDeref v) @v v)])))

(defn- deref-props
  [data]
  (into {} deref-map-xform data))

(defn- cursored-did-mount
  [state]
  (doseq [[k v] (:rum/props state)
          :when (satisfies? IWatchable v)]
    (add-watch v (cursored-key state)
               (fn [_ _ _ _]
                 (rum/request-render (:rum/react-component state)))))
  state)

(defn- cursored-will-umount
  [state]
  (doseq [[k v] (:rum/props state)
          :when (satisfies? IWatchable v)]
    (remove-watch v (cursored-key state)))
  state)

(defn- cursored-transfer-state
  [old new]
  (assoc new :rum/old-props (:rum/old-props old)))

(defn- cursored-should-update
  [old-state new-state]
  (not= (:rum/old-props old-state) (deref-props (:rum/props new-state))))

(defn- cursored-wrap-render
  [render-fn]
  (fn [state]
    (let [[dom next-state] (render-fn state)]
      [dom (assoc next-state :rum/old-props (deref-props (:rum/props state)))])))

(def cursored
  "A cursor like mixin that works with
  the `component` sugar syntax and lenses
  from the cats library."
  {:did-mount cursored-did-mount
   :will-unmount cursored-will-umount
   :transfer-state cursored-transfer-state
   :should-update cursored-should-update
   :wrap-render cursored-wrap-render})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lenses Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn derive
  [a path]
  (l/focus-atom (l/in path) a))

(defn focus
  ([state]
   (l/focus-atom l/id state))
  ([lens state]
   (l/focus-atom lens state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dom Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn prevent-default
  [e]
  (.preventDefault e))

(defn get-value
  [el]
  (gforms/getValue el))

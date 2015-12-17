(ns uxbox.ui.mixins
  (:refer-clojure :exclude [derive])
  (:require [rum.core :as rum]
            [cats.labs.lens :as l]
            [goog.dom.forms :as gforms]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Cursored & Lenses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- cursored-key
  [state]
  (str ":rum/cursored-" (:rum/id state)))

(def ^:private
  deref-xform
  (map (fn [x] (if (satisfies? IDeref x) @x x))))

(defn- deref-props
  [data]
  (into [] deref-xform data))

(defn- cursored-did-mount
  [state]
  (doseq [v (:rum/props state)
          :when (satisfies? IWatchable v)]
    (add-watch v (cursored-key state)
               (fn [_ _ _ _]
                 (rum/request-render (:rum/react-component state)))))
  state)

(defn- cursored-will-umount
  [state]
  (doseq [v (:rum/props state)
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

(defn local
  "Adds an atom to component’s state that can be used as local state.
   Atom is stored under key `:rum/local`.
   Component will be automatically re-rendered if atom’s value changes"
  ([]
   (local {} :rum/local))
  ([initial]
   (local initial :rum/local))
  ([initial key]
   {:transfer-state
    (fn [old new]
      (assoc new key (old key)))
    :will-mount
    (fn [state]
      (let [local-state (atom initial)
            component   (:rum/react-component state)]
        (add-watch local-state key
                   (fn [_ _ _ _]
                     (rum/request-render component)))
        (assoc state key local-state)))
    }))


(def static
  {:should-update
   (fn [old-state new-state]
     (not= (:rum/props old-state) (:rum/props new-state)))})

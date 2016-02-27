(ns uxbox.ui.mixins
  (:refer-clojure :exclude [derive])
  (:require [rum.core :as rum]
            [lentes.core :as l]
            [goog.dom.forms :as gforms]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rum Sugar
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
              ([& props]
               (let [state {:rum/props props}]
                 (rum/element cls state nil))))]
    (with-meta ctr {:rum/class cls})))

(defn ref-value
  [own ref]
  (let [component (-> own :rum/react-component)
        ref-node (aget (.-refs component) ref)
        dom-node  (.findDOMNode js/ReactDOM ref-node)]
    (.-value dom-node)))

(defn get-ref-dom
  [own ref]
  (let [component (-> own :rum/react-component)
        ref-node (aget (.-refs component) ref)]
    (.findDOMNode js/ReactDOM ref-node)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Cursored Mixin
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
  (let [key (gensym "foobar")]
    (doseq [v (:rum/props state)
            :when (satisfies? IWatchable v)]
      (add-watch v key
                 (fn [_ _ _ _]
                   (rum/request-render (:rum/react-component state)))))
    (assoc state ::key key)))

(defn- cursored-will-unmount
  [state]
  (let [key (::key state)]
    (doseq [v (:rum/props state)
            :when (satisfies? IWatchable v)]
      (remove-watch v key))
    (dissoc state ::key)))

(defn- cursored-transfer-state
  [old new]
  (assoc new
         :rum/old-props (:rum/old-props old)
         ::key (::key old)))

(defn- cursored-should-update
  [old-state new-state]
  (not= (:rum/old-props old-state) (deref-props (:rum/props new-state))))

(defn- cursored-wrap-render
  [render-fn]
  (fn [state]
    (let [[dom next-state] (render-fn state)]
      [dom (assoc next-state :rum/old-props (deref-props (:rum/props state)))])))

(def cursored
  {:transfer-state cursored-transfer-state
   :should-update cursored-should-update
   :wrap-render cursored-wrap-render})

(def cursored-watch
  {:did-mount cursored-did-mount
   :will-unmount cursored-will-unmount})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Local Mixin
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
                   (fn [_ _ oldv newv]
                     (when (not= oldv newv)
                       (rum/request-render component))))
        (assoc state key local-state)))
    }))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Static Mixin
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def static
  {:should-update
   (fn [old-state new-state]
     (not= (:rum/props old-state) (:rum/props new-state)))})

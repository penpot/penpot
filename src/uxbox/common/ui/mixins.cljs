(ns uxbox.common.ui.mixins
  (:refer-clojure :exclude [concat])
  (:require [sablono.core :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [goog.dom.forms :as gforms]))

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

(defn ref-html
  [own ref]
  (let [component (-> own :rum/react-component)
        node (aget (.-refs component) ref)]
    (.-innerHTML node)))

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

(defn concat
  [& elements]
  (html
   (for [[i element] (map-indexed vector elements)]
     (rum/with-key element (str i)))))

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

(def static
  {:should-update
   (fn [old-state new-state]
     (not= (:rum/props old-state) (:rum/props new-state)))})

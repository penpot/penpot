(ns uxbox.util.mixins
  (:refer-clojure :exclude [concat])
  (:require [sablono.core :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [goog.dom.forms :as gforms]))

(extend-type cljs.core.UUID
  INamed
  (-name [this] (str this))
  (-namespace [_] ""))

(defn component
  [{:keys [render] :as spec}]
  {:pre [(ifn? render)]}
  (let [name (or (:name spec) (str (gensym "rum-")))
        mixins (or (:mixins spec) [])
        spec (dissoc spec :name :mixins :render)
        render' (fn [state]
                  [(apply render state (:rum/args state)) state])
        mixins (conj mixins spec)]
    (rum/build-ctor render' mixins name)))

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

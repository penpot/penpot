(ns uxbox.ui.util
  "A collection of sugar syntax for define react
  components using rum libary."
  (:refer-clojure :exclude [derive])
  (:require [rum.core :as rum]))

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


(def mount rum/mount)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Color Conversion
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn hex->rgb
  [^string data]
  (some->> (re-find #"^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$" data)
           (rest)
           (mapv #(js/parseInt % 16))))

(defn rgb->hex
  [[r g b]]
  (letfn [(to-hex [c]
            (let [hexdata (.toString c 16)]
              (if (= (count hexdata) 1)
                (str "0" hexdata)
                hexdata)))]
    (str "#" (to-hex r) (to-hex g) (to-hex b))))

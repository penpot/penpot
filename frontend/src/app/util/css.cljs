(ns app.util.css
  (:require
   [app.common.data.macros :as dm]
   [app.util.dom :as dom]))

(defn declarations->str
  "Converts an object of CSS declarations to a string"
  [declarations]
  (let [entries (.from js/Array (.entries js/Object declarations))]
    (.reduce entries (fn [acc [k v]]
                       (dm/str acc k ": " v ";")) "")))

(defn add-rule
  "Adds a CSS rule to a CSS Style Sheet"
  [styleSheet selector declarations]
  (.insertRule styleSheet (dm/str selector " {" (declarations->str declarations) "}")))

;; FIXME: Maybe we should rename this to `create-dynamic-style`?
(defn create-style
  "Creates a new CSS Style Sheet and returns an object that allows adding rules to it"
  []
  (let [style (dom/create-element "style")]
    (dom/set-attribute! style "type" "text/css")
    (dom/append-child! js/document.head style)
    (js-obj "add" (partial add-rule (.-sheet style)))))



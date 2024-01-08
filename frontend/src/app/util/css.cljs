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
  (let [rule (dm/str selector " { " (declarations->str declarations) " }")]
    (.insertRule styleSheet rule (.-length (.-cssRules styleSheet)))))

(defn wrap-style-sheet
  [style]
  #js {:add (partial add-rule (.-sheet style))})

;; FIXME: Maybe we should rename this to `create-dynamic-style`?
(defn create-style
  "Creates a new CSS Style Sheet and returns an object that allows adding rules to it"
  [id]
  (let [element (dom/get-element id)]
    (if (some? element)
      (wrap-style-sheet element)
      (let [style (dom/create-element "style")]
        (dom/set-attribute! style "id" id)
        (dom/set-attribute! style "type" "text/css")
        (dom/append-child! js/document.head style)
        (wrap-style-sheet style)))))




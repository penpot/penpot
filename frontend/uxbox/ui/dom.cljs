(ns uxbox.ui.dom)

(defn stop-propagation
  [e]
  (.stopPropagation e))

(defn prevent-default
  [e]
  (.preventDefault e))

(defn event->inner-text
  [e]
  (.-innerText (.-target e)))

(defn event->value
  [e]
  (.-value (.-target e)))

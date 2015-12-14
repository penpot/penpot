(ns uxbox.ui.dom)

(defn stop-propagation
  [e]
  (.stopPropagation e))

(defn prevent-default
  [e]
  (.preventDefault e))

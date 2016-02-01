(ns uxbox.util.dom
  (:require [goog.dom :as dom]))

(defn get-element-by-class
  [^string classname]
  (dom/getElementByClass classname))

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

(defn event->target
  [e]
  (.-target e))

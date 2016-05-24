;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.util.dom
  (:require [goog.dom :as dom]))

;; --- Deprecated methods

(defn event->inner-text
  [e]
  (.-innerText (.-target e)))

(defn event->value
  [e]
  (.-value (.-target e)))

(defn event->target
  [e]
  (.-target e))

;; --- New methods

(defn get-element-by-class
  ([classname]
   (dom/getElementByClass classname))
  ([classname node]
   (dom/getElementByClass classname node)))

(defn stop-propagation
  [e]
  (when e
    (.stopPropagation e)))

(defn prevent-default
  [e]
  (when e
    (.preventDefault e)))

(defn get-target
  "Extract the target from event instance."
  [event]
  (.-target event))

(defn get-value
  "Extract the value from dom node."
  [node]
  (.-value node))

(defn click
  "Click a node"
  [node]
  (.click node))

(defn get-files
  "Extract the files from dom node."
  [node]
  (.-files node))

(defn checked?
  "Check if the node that reprsents a radio
  or checkbox is checked or not."
  [node]
  (.-checked node))

(defn clean-value!
  [node]
  (set! (.-value node) ""))

(defn ^boolean equals?
  [node-a node-b]
  (.isEqualNode node-a node-b))

(defn get-event-files
  "Extract the files from event instance."
  [event]
  (get-files (get-target event)))

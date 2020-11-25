;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.util.dom
  (:require
   [app.common.exceptions :as ex]
   [app.common.geom.point :as gpt]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [goog.dom :as dom]))

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

(defn classnames
  [& params]
  (assert (even? (count params)))
  (str/join " " (reduce (fn [acc [k v]]
                          (if (true? (boolean v))
                            (conj acc (name k))
                            acc))
                        []
                        (partition 2 params))))

;; --- New methods

(defn get-element-by-class
  ([classname]
   (dom/getElementByClass classname))
  ([classname node]
   (dom/getElementByClass classname node)))

(defn get-element
  [id]
  (dom/getElement id))

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

(defn get-parent
  [dom]
  (.-parentElement ^js dom))

(defn get-value
  "Extract the value from dom node."
  [node]
  (.-value node))

(def get-target-val (comp get-value get-target))

(defn click
  "Click a node"
  [node]
  (.click node))

(defn get-files
  "Extract the files from dom node."
  [node]
  (array-seq (.-files node)))

(defn checked?
  "Check if the node that represents a radio
  or checkbox is checked or not."
  [node]
  (.-checked node))

(defn valid?
  "Check if the node that is a form input
  has a valid value, against html5 form validation
  properties (required, min/max, pattern...)."
  [node]
  (.-valid (.-validity node)))

(defn clean-value!
  [node]
  (set! (.-value node) ""))

(defn set-value!
  [node value]
  (set! (.-value node) value))

(defn select-text!
  [node]
  (.select ^js node))

(defn ^boolean equals?
  [node-a node-b]
  (.isEqualNode ^js node-a node-b))

(defn get-event-files
  "Extract the files from event instance."
  [event]
  (get-files (get-target event)))

(defn create-element
  ([tag]
   (.createElement js/document tag))
  ([ns tag]
   (.createElementNS js/document ns tag)))

(defn set-html!
  [el html]
  (set! (.-innerHTML el) html))

(defn append-child!
  [el child]
  (.appendChild el child))

(defn get-first-child
  [el]
  (.-firstChild el))

(defn get-tag-name
  [el]
  (.-tagName el))

(defn get-outer-html
  [el]
  (.-outerHTML el))

(defn get-inner-text
  [el]
  (.-innerText el))

(defn query
  [el query]
  (.querySelector el query))

(defn get-client-position
  [event]
  (let [x (.-clientX event)
        y (.-clientY event)]
    (gpt/point x y)))

(defn get-offset-position
  [event]
  (let [x (.-offsetX event)
        y (.-offsetY event)]
    (gpt/point x y)))

(defn get-client-size
  [node]
  {:width (.-clientWidth ^js node)
   :height (.-clientHeight ^js node)})

(defn get-bounding-rect
  [node]
  (let [rect (.getBoundingClientRect ^js node)]
    {:left (.-left ^js rect)
     :top (.-top ^js rect)
     :right (.-right ^js rect)
     :bottom (.-bottom ^js rect)
     :width (.-width ^js rect)
     :height (.-height ^js rect)}))

(defn get-window-size
  []
  {:width (.-innerWidth ^js js/window)
   :height (.-innerHeight ^js js/window)})

(defn focus!
  [node]
  (.focus node))

(defn fullscreen?
  []
  (cond
    (obj/in? js/document "webkitFullscreenElement")
    (boolean (.-webkitFullscreenElement js/document))

    (obj/in? js/document "fullscreenElement")
    (boolean (.-fullscreenElement js/document))

    :else
    (ex/raise :type :not-supported
              :hint "seems like the current browset does not support fullscreen api.")))

(defn ^boolean blob?
  [v]
  (instance? js/Blob v))

(defn create-blob
  "Create a blob from content."
  ([content]
   (create-blob content "application/octet-stream"))
  ([content mimetype]
   (js/Blob. #js [content] #js {:type mimetype})))

(defn revoke-uri
  [url]
  (js/URL.revokeObjectURL url))

(defn create-uri
  "Create a url from blob."
  [b]
  {:pre [(blob? b)]}
  (js/URL.createObjectURL b))

(defn set-css-property [node property value]
  (.setProperty (.-style ^js node) property value))

(defn capture-pointer [event]
  (-> event get-target (.setPointerCapture (.-pointerId event))))

(defn release-pointer [event]
  (-> event get-target (.releasePointerCapture (.-pointerId event))))

(defn get-root []
  (query js/document "#app"))

(defn ^boolean class? [node class-name]
  (let [class-list (.-classList ^js node)]
    (.contains ^js class-list class-name)))

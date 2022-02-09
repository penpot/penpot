;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.dom
  (:require
    [app.common.exceptions :as ex]
    [app.common.geom.point :as gpt]
    [app.util.globals :as globals]
    [app.util.object :as obj]
    [cuerdas.core :as str]
    [goog.dom :as dom]
    [promesa.core :as p]))

;; --- Deprecated methods

(defn event->inner-text
  [^js e]
  (when (some? e)
    (.-innerText (.-target e))))

(defn event->value
  [^js e]
  (when (some? e)
    (.-value (.-target e))))

(defn event->target
  [^js e]
  (when (some? e)
    (.-target e)))

;; --- New methods

(defn set-html-title
  [^string title]
  (set! (.-title globals/document) title))

(defn set-page-style
  [style]
  (let [head (first (.getElementsByTagName ^js globals/document "head"))
        style-str (str/join "\n"
                            (map (fn [[k v]]
                                   (str (name k) ": " v ";"))
                                 style))]
    (.insertAdjacentHTML head "beforeend"
                         (str "<style>"
                              "  @page {" style-str "}"
                              "  html, body {"            ; Fix issue having Chromium to add random 1px margin at the bottom
                              "    overflow: hidden;"     ; https://github.com/puppeteer/puppeteer/issues/2278#issuecomment-410381934
                              "    font-size: 0;"
                              "  }"
                              "</style>"))))

(defn get-element-by-class
  ([classname]
   (dom/getElementByClass classname))
  ([classname node]
   (dom/getElementByClass classname node)))

(defn get-element
  [id]
  (dom/getElement id))

(defn get-elements-by-tag
  [^js node tag]
  (when (some? node)
    (.getElementsByTagName node tag)))

(defn stop-propagation
  [^js event]
  (when event
    (.stopPropagation event)))

(defn prevent-default
  [^js event]
  (when event
    (.preventDefault event)))

(defn get-target
  "Extract the target from event instance."
  [^js event]
  (when (some? event)
    (.-target event)))

(defn get-current-target
  "Extract the current target from event instance (different from target
   when event triggered in a child of the subscribing element)."
  [^js event]
  (when (some? event)
    (.-currentTarget event)))

(defn get-parent
  [^js node]
  (when (some? node)
    (.-parentElement ^js node)))

(defn get-value
  "Extract the value from dom node."
  [^js node]
  (when (some? node)
    (.-value node)))

(defn get-attribute
  "Extract the value of one attribute of a dom node."
  [^js node ^string attr-name]
  (when (some? node)
    (.getAttribute ^js node attr-name)))

(def get-target-val (comp get-value get-target))

(defn click
  "Click a node"
  [^js node]
  (when (some? node)
    (.click node)))

(defn get-files
  "Extract the files from dom node."
  [^js node]
  (when (some? node)
    (array-seq (.-files node))))

(defn checked?
  "Check if the node that represents a radio
  or checkbox is checked or not."
  [^js node]
  (when (some? node)
    (.-checked node)))

(defn valid?
  "Check if the node that is a form input
  has a valid value, against html5 form validation
  properties (required, min/max, pattern...)."
  [^js node]
  (when (some? node)
    (when-let [validity (.-validity node)]
      (.-valid validity))))

(defn set-validity!
  "Manually set the validity status of a node that
  is a form input. If the state is an empty string,
  the input will be valid. If not, the string will
  be set as the error message."
  [^js node status]
  (when (some? node)
    (.setCustomValidity node status)
    (.reportValidity node)))

(defn clean-value!
  [^js node]
  (when (some? node)
    (set! (.-value node) "")))

(defn set-value!
  [^js node value]
  (when (some? node)
    (set! (.-value ^js node) value)))

(defn select-text!
  [^js node]
  (when (some? node)
    (.select ^js node)))

(defn ^boolean equals?
  [^js node-a ^js node-b]

  (or (and (nil? node-a) (nil? node-b))
      (and (some? node-a)
           (.isEqualNode ^js node-a node-b))))

(defn get-event-files
  "Extract the files from event instance."
  [^js event]
  (when (some? event)
    (get-files (get-target event))))

(defn create-element
  ([tag]
   (.createElement globals/document tag))
  ([ns tag]
   (.createElementNS globals/document ns tag)))

(defn set-html!
  [^js el html]
  (when (some? el)
    (set! (.-innerHTML el) html)))

(defn append-child!
  [^js el child]
  (when (some? el)
    (.appendChild ^js el child)))

(defn get-first-child
  [^js el]
  (when (some? el)
    (.-firstChild el)))

(defn get-tag-name
  [^js el]
  (when (some? el)
    (.-tagName el)))

(defn get-outer-html
  [^js el]
  (when (some? el)
    (.-outerHTML el)))

(defn get-inner-text
  [^js el]
  (when (some? el)
    (.-innerText el)))

(defn query
  ([^string query]
   (query globals/document query))

  ([^js el ^string query]
   (when (some? el)
     (.querySelector el query))))

(defn query-all
  ([^string query]
   (query-all globals/document query))

  ([^js el ^string query]
   (when (some? el)
     (.querySelectorAll el query))))

(defn get-client-position
  [^js event]
  (let [x (.-clientX event)
        y (.-clientY event)]
    (gpt/point x y)))

(defn get-offset-position
  [^js event]
  (when (some? event)
    (let [x (.-offsetX event)
          y (.-offsetY event)]
      (gpt/point x y))))

(defn get-client-size
  [^js node]
  (when (some? node)
    {:width (.-clientWidth ^js node)
     :height (.-clientHeight ^js node)}))

(defn get-bounding-rect
  [node]
  (let [rect (.getBoundingClientRect ^js node)]
    {:left (.-left ^js rect)
     :top (.-top ^js rect)
     :right (.-right ^js rect)
     :bottom (.-bottom ^js rect)
     :width (.-width ^js rect)
     :height (.-height ^js rect)}))

(defn bounding-rect->rect
  [{:keys [left top width height]}]
  {:x left
   :y top
   :width width
   :height height})

(defn get-window-size
  []
  {:width (.-innerWidth ^js js/window)
   :height (.-innerHeight ^js js/window)})

(defn focus!
  [^js node]
  (when (some? node)
    (.focus node)))

(defn blur!
  [^js node]
  (when (some? node)
    (.blur node)))

(defn fullscreen?
  []
  (cond
    (obj/in? globals/document "webkitFullscreenElement")
    (boolean (.-webkitFullscreenElement globals/document))

    (obj/in? globals/document "fullscreenElement")
    (boolean (.-fullscreenElement globals/document))

    :else
    (ex/raise :type :not-supported
              :hint "seems like the current browser does not support fullscreen api.")))

(defn ^boolean blob?
  [^js v]
  (when (some? v)
    (instance? js/Blob v)))

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

(defn set-property! [^js node property value]
  (when (some? node)
    (.setAttribute node property value)))

(defn set-text! [^js node text]
  (when (some? node)
    (set! (.-textContent node) text)))

(defn set-css-property! [^js node property value]
  (when (some? node)
    (.setProperty (.-style ^js node) property value)))

(defn capture-pointer [^js event]
  (when (some? event)
    (-> event get-target (.setPointerCapture (.-pointerId event)))))

(defn release-pointer [^js event]
  (when (and (some? event) (.-pointerId event))
    (-> event get-target (.releasePointerCapture (.-pointerId event)))))

(defn get-root []
  (query globals/document "#app"))

(defn classnames
  [& params]
  (assert (even? (count params)))
  (str/join " " (reduce (fn [acc [k v]]
                          (if (true? (boolean v))
                            (conj acc (name k))
                            acc))
                        []
                        (partition 2 params))))

(defn ^boolean class? [node class-name]
  (when (some? node)
    (let [class-list (.-classList ^js node)]
      (.contains ^js class-list class-name))))

(defn add-class! [^js node class-name]
  (when (some? node)
    (let [class-list (.-classList ^js node)]
      (.add ^js class-list class-name))))

(defn remove-class! [^js node class-name]
  (when (some? node)
    (let [class-list (.-classList ^js node)]
      (.remove ^js class-list class-name))))

(defn child? [^js node1 ^js node2]
  (when (some? node1)
    (.contains ^js node2 ^js node1)))

(defn get-user-agent []
  (.-userAgent globals/navigator))

(defn get-active []
  (.-activeElement globals/document))

(defn active? [^js node]
  (when (some? node)
    (= (get-active) node)))

(defn get-data [^js node ^string attr]
  (when (some? node)
    (.getAttribute node (str "data-" attr))))

(defn mtype->extension [mtype]
  ;; https://developer.mozilla.org/en-US/docs/Web/HTTP/Basics_of_HTTP/MIME_types
  (case mtype
    "image/apng"         "apng"
    "image/avif"         "avif"
    "image/gif"          "gif"
    "image/jpeg"         "jpg"
    "image/png"          "png"
    "image/svg+xml"      "svg"
    "image/webp"         "webp"
    "application/zip"    "zip"
    "application/penpot" "penpot"
    nil))

(defn set-attribute [^js node ^string attr value]
  (when (some? node)
    (.setAttribute node attr value)))

(defn remove-attribute [^js node ^string attr]
  (when (some? node)
    (.removeAttribute node attr)))

(defn get-scroll-pos
  [^js element]
  (when (some? element)
    (.-scrollTop element)))

(defn set-scroll-pos!
  [^js element scroll]
  (when (some? element)
    (obj/set! element "scrollTop" scroll)))

(defn scroll-into-view!
  ([^js element]
   (scroll-into-view! element false))

  ([^js element options]
   (when (some? element)
     (.scrollIntoView element options))))

(defn scroll-into-view-if-needed!
  ([^js element]
   (scroll-into-view-if-needed! element false))

  ([^js element options]
   (when (some? element)
     (.scrollIntoViewIfNeeded ^js element options))))

(defn is-in-viewport?
  [^js element]
  (when (some? element)
    (let [rect   (.getBoundingClientRect element)
          height (or (.-innerHeight js/window)
                     (.. js/document -documentElement -clientHeight))
          width  (or (.-innerWidth js/window)
                     (.. js/document -documentElement -clientWidth))]
      (and (>= (.-top rect) 0)
           (>= (.-left rect) 0)
           (<= (.-bottom rect) height)
           (<= (.-right rect) width)))))

(defn trigger-download-uri
  [filename mtype uri]
  (let [link (create-element "a")
        extension (mtype->extension mtype)
        filename (if extension
                   (str filename "." extension)
                   filename)]
    (obj/set! link "href" uri)
    (obj/set! link "download" filename)
    (obj/set! (.-style ^js link) "display" "none")
    (.appendChild (.-body ^js js/document) link)
    (.click link)
    (.remove link)))

(defn trigger-download
  [filename blob]
  (trigger-download-uri filename (.-type ^js blob) (create-uri blob)))

(defn save-as
  [uri filename mtype description]

  ;; Only chrome supports the save dialog
  (if (obj/contains? globals/window "showSaveFilePicker")
    (let [extension (mtype->extension mtype)
          opts {:suggestedName (str filename "." extension)
                :types [{:description description
                         :accept { mtype [(str "." extension)]}}]}]

      (-> (p/let [file-system (.showSaveFilePicker globals/window (clj->js opts))
                  writable    (.createWritable file-system)
                  response    (js/fetch uri)
                  blob        (.blob response)
                  _           (.write writable blob)]
            (.close writable))
          (p/catch
              #(when-not (and (= (type %) js/DOMException)
                              (= (.-name %) "AbortError"))
                 (trigger-download-uri filename mtype uri)))))

    (trigger-download-uri filename mtype uri)))

(defn left-mouse? [bevent]
  (let [event  (.-nativeEvent ^js bevent)]
    (= 1 (.-which event))))

;; Warning: need to protect against reverse tabnabbing attack
;; https://www.comparitech.com/blog/information-security/reverse-tabnabbing/
(defn open-new-window
  ([uri]
   (open-new-window uri "_blank" "noopener,noreferrer"))
  ([uri name]
   (open-new-window uri name "noopener,noreferrer"))
  ([uri name features]
   (let [new-window (.open js/window (str uri) name features)]
     (when (not= name "_blank")
       (.reload (.-location new-window))))))

(defn browser-back
  []
  (.back (.-history js/window)))

(defn animate!
  ([item keyframes duration] (animate! item keyframes duration nil))
  ([item keyframes duration onfinish]
    (let [animation (.animate item keyframes duration)]
      (when onfinish
        (set! (.-onfinish animation) onfinish)))))


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.dom
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.logging :as log]
   [app.common.media :as cm]
   [app.util.globals :as globals]
   [app.util.object :as obj]
   [app.util.webapi :as wapi]
   [cuerdas.core :as str]
   [goog.dom :as dom]
   [potok.v2.core :as ptk]
   [promesa.core :as p])
  (:import goog.events.BrowserEvent))

(extend-type BrowserEvent
  cljs.core/IDeref
  (-deref [it] (.getBrowserEvent it)))

(declare get-window-size)

(defn browser-event?
  [o]
  (instance? BrowserEvent o))

(defn native-event?
  [o]
  (instance? js/Event o))

(log/set-level! :warn)

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

(defn event->native-event
  [^js e]
  (.-nativeEvent e))

(defn event->browser-event
  [^js e]
  (.getBrowserEvent e))

;; --- New methods

(declare get-elements-by-tag)

(defn set-html-title
  [^string title]
  (set! (.-title globals/document) title))

(defn set-page-style!
  [styles]
  (let [node  (first (get-elements-by-tag globals/document "head"))
        style (reduce-kv (fn [res k v]
                           (conj res (dm/str (str/css-selector k) ":" v ";")))
                         []
                         styles)
        style (dm/str "<style>\n"
                      "  @page {" (str/join " " style) "}\n "
                      "  html, body {font-size:0; margin:0; padding:0}\n "
                      "</style>")]
    (.insertAdjacentHTML ^js node "beforeend" style)))

(defn get-element-by-class
  ([classname]
   (dom/getElementByClass classname))
  ([classname node]
   (dom/getElementByClass classname node)))

(defn get-elements-by-class
  ([classname]
   (dom/getElementsByClass classname))
  ([classname node]
   (dom/getElementsByClass classname node)))

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

(defn stop-immediate-propagation
  [^js event]
  (when event
    (.stopImmediatePropagation event)))

(defn prevent-default
  [^js event]
  (when event
    (.preventDefault event)))

(defn get-target
  "Extract the target from event instance."
  [^js event]
  (when (some? event)
    (.-target event)))

(defn get-related-target
  "Extract the related target from a blur or focus event instance."
  [^js event]
  (when (some? event)
    (.-relatedTarget event)))

(defn select-target
  "Extract the target from event instance and select it"
  [^js event]
  (when (some? event)
    (-> event (.-target) (.select))))

(defn select-node
  "Select element by node"
  [^js node]
  (when (some? node)
    (.-select node)))

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

(defn get-parent-at
  [^js node count]
  (when (some? node)
    (loop [current node current-count count]
      (if (or (nil? current) (= current-count 0))
        current
        (recur (.-parentElement current) (dec current-count))))))

(defn get-parent-with-data
  [^js node name]
  (let [name (str/camel name)]
    (loop [current node]
      (if (or (nil? current) (obj/in? (.-dataset current) name))
        current
        (recur (.-parentElement current))))))

(defn get-parent-with-selector
  [^js node selector]
  (loop [current node]
    (if (or (nil? current) (.matches current selector))
      current
      (recur (.-parentElement current)))))

(defn get-value
  "Extract the value from dom node."
  [^js node]
  (when (some? node)
    (.-value node)))

(defn get-input-value
  "Extract the value from dom input node taking into account the type."
  [^js node]
  (when (some? node)
    (if (or (= (.-type node) "checkbox")
            (= (.-type node) "radio"))
      (.-checked node)
      (.-value node))))

(defn get-attribute
  "Extract the value of one attribute of a dom node."
  [^js node ^string attr-name]
  (when (some? node)
    (.getAttribute ^js node attr-name)))

(defn get-scroll-position
  [^js event]
  (when (some? event)
    {:scroll-height (.-scrollHeight event)
     :scroll-left   (.-scrollLeft event)
     :scroll-top    (.-scrollTop event)
     :scroll-width  (.-scrollWidth event)}))

(defn get-scroll-height-ratio
  [^js node]
  (when (some? node)
    (/ (.-scrollHeight node) (.-clientHeight node))))

(defn get-scroll-distance
  [^js node scroll-node]
  (when (and (some? node) (some? scroll-node))
    (abs (- (.-scrollTop scroll-node) (.-offsetTop node)))))

(defn get-scroll-distance-ratio
  [^js node scroll-node]
  (let [distance (get-scroll-distance node scroll-node)
        height   (.-clientHeight scroll-node)]
    (/ distance height)))

(def get-target-val (comp get-value get-target))

(def get-target-scroll (comp get-scroll-position get-target))

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
  (when (and (some? node) (some? (unchecked-get node "select")))
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
   (.createElementNS globals/document ns tag))
  ([document ns tag]
   (.createElementNS document ns tag)))

(defn create-text
  ([^js text]
   (create-text globals/document text))
  ([document ^js text]
   (.createTextNode document text)))

(defn set-html!
  [^js el html]
  (when (some? el)
    (set! (.-innerHTML el) html))
  el)

(defn append-child!
  [^js el child]
  (when (some? el)
    (.appendChild ^js el child))
  el)

(defn insert-after!
  [^js el ^js ref child]
  (when (and (some? el) (some? ref))
    (let [nodes (.-childNodes el)
          idx   (d/index-of-pred nodes #(= ref %))]
      (if-let [sibnode (unchecked-get nodes (inc idx))]
        (.insertBefore el child sibnode)
        (.appendChild ^js el child))))
  el)

(defn remove-child!
  [^js el child]
  (when (some? el)
    (.removeChild ^js el child))
  el)

(defn remove!
  [^js el]
  (when (some? el)
    (.remove ^js el)))

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
  ([^string selector]
   (query globals/document selector))

  ([^js el ^string selector]
   (when (some? el)
     (.querySelector el selector))))

(defn query-all
  ([^string selector]
   (query-all globals/document selector))

  ([^js el ^string selector]
   (when (some? el)
     (.querySelectorAll el selector))))

(defn get-element-offset-position
  [^js node]
  (when (some? node)
    (let [x (.-offsetTop node)
          y (.-offsetLeft node)]
      (gpt/point x y))))

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

(defn get-delta-position
  [event]
  (let [e (if (browser-event? event)
            (deref event)
            event)
        x (.-deltaX ^js e)
        y (.-deltaY ^js e)]
    (gpt/point x y)))

(defn get-client-size
  [^js node]
  (when (some? node)
    (grc/make-rect 0 0 (.-clientWidth node) (.-clientHeight node))))

(defn get-bounding-rect
  [node]
  (let [rect (.getBoundingClientRect ^js node)]
    {:left (.-left ^js rect)
     :top (.-top ^js rect)
     :right (.-right ^js rect)
     :bottom (.-bottom ^js rect)
     :width (.-width ^js rect)
     :height (.-height ^js rect)}))

(defn is-bounding-rect-outside?
  [{:keys [left top right bottom]} {:keys [width height]}]
  (or (< left 0)
      (< top 0)
      (> right width)
      (> bottom height)))

(defn is-element-outside?
  [element]
  (is-bounding-rect-outside? (get-bounding-rect element)
                             (get-window-size)))

(defn bounding-rect->rect
  [rect]
  (when (some? rect)
    (grc/make-rect
     (or (.-left rect)   (:left rect)   0)
     (or (.-top rect)    (:top rect)    0)
     (or (.-width rect)  (:width rect)  1)
     (or (.-height rect) (:height rect) 1))))

(defn get-window-size
  []
  {:width (.-innerWidth ^js js/window)
   :height (.-innerHeight ^js js/window)})

(defn get-window-height
  []
  (.-innerHeight ^js js/window))

(defn get-computed-styles
  [node]
  (js/getComputedStyle node))

(defn get-property-value
  [o prop]
  (.getPropertyValue ^js o prop))

(defn get-css-variable
  ([variable element]
   (.getPropertyValue (.getComputedStyle js/window element) variable))
  ([variable]
   (.getPropertyValue (.getComputedStyle js/window (.-documentElement js/document)) variable)))

(defn focus!
  [^js node]
  (when (some? node)
    (.focus node)))

(defn click!
  [^js node]
  (when (some? node)
    (.click node)))

(defn focus?
  [^js node]
  (and node
       (= (.-activeElement js/document) node)))

(defn blur!
  [^js node]
  (when (some? node)
    (.blur node)))

;; List of dom events for different browsers to detect the exit of fullscreen mode
(def fullscreen-events
  ["fullscreenchange" "mozfullscreenchange" "MSFullscreenChange" "webkitfullscreenchange"])

(defn fullscreen?
  []
  (cond
    (obj/in? globals/document "webkitFullscreenElement")
    (boolean (.-webkitFullscreenElement globals/document))

    (obj/in? globals/document "mozFullScreen")
    (boolean (.-mozFullScreen globals/document))

    (obj/in? globals/document "msFullscreenElement")
    (boolean (.-msFullscreenElement globals/document))

    (obj/in? globals/document "fullscreenElement")
    (boolean (.-fullscreenElement globals/document))

    :else
    (do
      (log/error :msg "Seems like the current browser does not support fullscreen api.")
      false)))

(defn blob?
  [^js v]
  (when (some? v)
    (instance? js/Blob v)))

(defn make-node
  ([namespace name]
   (.createElementNS globals/document namespace name))

  ([name]
   (.createElement globals/document name)))

(defn node->xml
  [node]
  (when (some? node)
    (->  (js/XMLSerializer.)
         (.serializeToString node))))

(defn str->data-uri
  [str type]
  (assert (string? str))
  (let [b64 (-> str
                js/btoa)]
    (dm/str "data:" type ";base64," b64)))

(defn svg-node->data-uri
  [svg-node]
  (let [xml  (-> (js/XMLSerializer.)
                 (.serializeToString svg-node))
        data-uri (str->data-uri xml "image/svg+xml")]
    data-uri))

(defn set-property! [^js node property value]
  (when (some? node)
    (.setAttribute node property value))
  node)

(defn get-text
  [^js node]
  (when (some? node)
    (.-textContent node)))

(defn set-text! [^js node text]
  (when (some? node)
    (set! (.-textContent node) text))
  node)

(defn set-css-property! [^js node property value]
  (when (some? node)
    (.setProperty (.-style ^js node) property value))
  node)

(defn unset-css-property! [^js node property]
  (when (some? node)
    (.removeProperty (.-style ^js node) property))
  node)

(defn capture-pointer [^js event]
  (when (some? event)
    (-> event get-target (.setPointerCapture (.-pointerId event)))))

(defn release-pointer [^js event]
  (when (and (some? event) (.-pointerId event))
    (-> event get-target (.releasePointerCapture (.-pointerId event)))))

(defn get-body []
  (.-body globals/document))

(defn get-root []
  (query globals/document "#app"))

(defn classnames
  [& params]
  (assert (even? (count params)))
  (str/join " " (reduce (fn [acc [k v]]
                          (if (true? (boolean v))
                            (conj acc (d/name k))
                            acc))
                        []
                        (partition 2 params))))

(defn ^boolean id?
  [node id]
  (when (some? node)
    (= (.-id ^js node) id)))

(defn ^boolean class? [node class-name]
  (when (some? node)
    (let [class-list (.-classList ^js node)]
      (.contains ^js class-list class-name))))

(defn add-class! [^js node class-name]
  (when (some? node)
    (let [class-list (.-classList ^js node)]
      (.add ^js class-list class-name)))
  node)

(defn remove-class! [^js node class-name]
  (when (some? node)
    (let [class-list (.-classList ^js node)]
      (.remove ^js class-list class-name))))

(defn child? [^js node1 ^js node2]
  (when (and (some? node1) (some? node2))
    (.contains ^js node2 ^js node1)))

(defn get-active []
  (.-activeElement globals/document))

(defn active? [^js node]
  (when (some? node)
    (= (get-active) node)))

(defn get-data
  [^js node ^string attr]
  ;; NOTE: we use getAttribute instead of .dataset for performance
  ;; reasons. The getAttribute is x2 faster than dataset. See more on:
  ;; https://www.measurethat.net/Benchmarks/Show/14432/0/getattribute-vs-dataset
  (when (some? node)
    (.getAttribute node (dm/str "data-" attr))))

(defn- resolve-node
  [event]
  (cond
    (instance? js/Element event)
    event

    :else
    (get-current-target event)))

(defn get-boolean-data
  [node attr]
  (some-> (resolve-node node)
          (get-data attr)
          (parse-boolean)))

(defn set-data!
  [^js node ^string attr value]
  (when (some? node)
    (.setAttribute node (dm/str "data-" attr) (dm/str value)))
  node)

(defn set-attribute! [^js node ^string attr value]
  (when (some? node)
    (.setAttribute node attr value)))

(defn set-style!
  [^js node ^string style value]
  (when (some? node)
    (.setProperty (.-style node) style value)))

(defn remove-attribute! [^js node ^string attr]
  (when (some? node)
    (.removeAttribute node attr)))

(defn get-scroll-pos
  [^js element]
  (when (some? element)
    (.-scrollTop element)))

(defn get-h-scroll-pos
  [^js element]
  (when (some? element)
    (.-scrollLeft element)))

(defn scroll-to
  ([^js element options]
   (.scrollTo element options))
  ([^js element x y]
   (.scrollTo element x y)))

(defn set-scroll-pos!
  [^js element scroll]
  (when (some? element)
    (obj/set! element "scrollTop" scroll)))

(defn set-h-scroll-pos!
  [^js element scroll]
  (when (some? element)
    (obj/set! element "scrollLeft" scroll)))

(defn scroll-into-view!
  ([^js element]
   (scroll-into-view! element false))

  ([^js element options]
   (when (some? element)
     (.scrollIntoView element options))))

;; NOTE: scrollIntoViewIfNeeded is not supported in Firefox
;; because it is not a standard API. BTW it only supports
;; centerIfNeeded as boolean option.
;; @see https://developer.mozilla.org/en-US/docs/Web/API/Element/scrollIntoViewIfNeeded
;; @see https://www.w3.org/Bugs/Public/show_bug.cgi?id=17152
;; @see https://github.com/w3c/csswg-drafts/pull/1805
;; @see https://github.com/w3c/csswg-drafts/pull/5677
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
  (let [link      (create-element "a")
        extension (cm/mtype->extension mtype)
        filename  (if (and extension (not (str/ends-with? filename extension)))
                    (str/concat filename extension)
                    filename)]
    (obj/set! link "href" uri)
    (obj/set! link "download" filename)
    (obj/set! (.-style ^js link) "display" "none")
    (.appendChild (.-body ^js js/document) link)
    (.click link)
    (.remove link)))

(defn trigger-download
  [filename blob]
  (trigger-download-uri filename (.-type ^js blob) (wapi/create-uri blob)))

(defn event
  "Create an instance of DOM Event"
  ([^string type]
   (js/Event. type))
  ([^string type options]
   (js/Event. type options)))

(defn dispatch-event
  [target event]
  (when (some? target)
    (.dispatchEvent ^js target event)))


(defn save-as
  [uri filename mtype description]

  ;; Only chrome supports the save dialog
  (if (obj/contains? globals/window "showSaveFilePicker")
    (let [extension (cm/mtype->extension mtype)
          opts {:suggestedName (str filename "." extension)
                :types [{:description description
                         :accept {mtype [(str "." extension)]}}]}]

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

(defn left-mouse?
  [bevent]
  (let [event (.-nativeEvent ^js bevent)]
    (= 1 (.-which event))))

(defn middle-mouse?
  [bevent]
  (let [event (.-nativeEvent ^js bevent)]
    (= 2 (.-which event))))


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

(defn reload-current-window
  ([]
   (.reload globals/location))
  ([force?]
   (.reload globals/location force?)))

(defn scroll-by!
  ([element x y]
   (.scrollBy ^js element x y))
  ([x y]
   (scroll-by! js/window x y)))

(defn animate!
  ([item keyframes duration] (animate! item keyframes duration nil))
  ([item keyframes duration onfinish]
   (let [animation (.animate item keyframes duration)]
     (when onfinish
       (set! (.-onfinish animation) onfinish)))))

(defn is-child?
  [^js node ^js candidate]
  (and (some? node)
       (some? candidate)
       (.contains node candidate)))

(defn seq-nodes
  [root-node]
  (letfn [(branch? [node]
            (d/not-empty? (get-children node)))

          (get-children [node]
            (seq (.-children node)))]
    (->> root-node
         (tree-seq branch? get-children))))

(defn check-font? [font]
  (let [fonts (.-fonts globals/document)]
    (.check fonts font)))

(defn load-font [font]
  (let [fonts (.-fonts globals/document)]
    (.load fonts font)))

(defn text-measure [font]
  (let [element (.createElement globals/document "canvas")
        context (.getContext element "2d")
        _ (set! (.-font context) font)
        measure ^js (.measureText context "Ag")]

    {:ascent (.-fontBoundingBoxAscent measure)
     :descent (.-fontBoundingBoxDescent measure)}))

(defn clone-node
  ([^js node]
   (clone-node node true))
  ([^js node deep?]
   (.cloneNode node deep?)))

(defn get-children
  [node]
  (when (some? node)
    (.-children node)))

(defn has-children?
  [^js node]
  (> (-> node .-children .-length) 0))

;; WARNING: Use only for debugging. It's to costly to use for real
(defn measure-text
  "Given a canvas' context 2d and the text info returns tis ascent/descent info"
  [context-2d font-size font-family text]
  (let [_ (set! (.-font context-2d) (str font-size " " font-family))
        measures (.measureText context-2d text)]
    {:descent (.-actualBoundingBoxDescent measures)
     :ascent (.-actualBoundingBoxAscent measures)}))

(defmethod ptk/resolve ::focus-element
  [_ {:keys [name]}]
  (ptk/reify ::focus-element
    ptk/EffectEvent
    (effect [_ _ _]
      (focus! (get-element name)))))

(defn first-child
  [^js node]
  (.. node -firstChild))

(defn last-child
  [^js node]
  (.. node -lastChild))

(defn prevent-browser-gesture-navigation!
  []
  ;; Prevent the browser from interpreting trackpad horizontal swipe as back/forth
  ;;
  ;; In theory We could disable this only for the workspace. However gets too unreliable.
  ;; It is better to be safe and disable for the dashboard as well.
  (set! (.. js/document -documentElement -style -overscrollBehaviorX) "none")
  (set! (.. js/document -body -style -overscrollBehaviorX) "none"))

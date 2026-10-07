;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.shapes.text.ime-debug
  "Read-only IME geometry overlay. Enable with debug.toggle_debug('ime')."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.config :as cf]
   [app.render-wasm.text-editor :as text-editor]
   [app.util.debug :as dbg]
   [rumext.v2 :as mf]))

(defn- screen-rect
  [{:keys [x y width height]} ^js matrix]
  (let [points (for [[px py] [[x y] [(+ x width) y]
                              [x (+ y height)] [(+ x width) (+ y height)]]]
                 [(+ (* (.-a matrix) px) (* (.-c matrix) py) (.-e matrix))
                  (+ (* (.-b matrix) px) (* (.-d matrix) py) (.-f matrix))])
        xs     (map first points)
        ys     (map second points)
        left   (apply min xs)
        top    (apply min ys)]
    {:x left :y top :width (- (apply max xs) left) :height (- (apply max ys) top)}))

(defn- dom-rect
  [^js rect]
  {:x (.-x rect) :y (.-y rect) :width (.-width rect) :height (.-height rect)})

(defn- text-position
  "Resolve a UTF-16 offset, keeping range edges inside the composing text."
  [nodes offset edge]
  (loop [[node & remaining] nodes
         offset offset]
    (when node
      (if (or (< offset (.-length ^js node))
              (and (= offset (.-length ^js node))
                   (or (= edge :end) (empty? remaining))))
        [node offset]
        (recur remaining (- offset (.-length ^js node)))))))

(defn- composition-range
  [^js node {:keys [start text]}]
  (let [walker (.createTreeWalker js/document node js/NodeFilter.SHOW_TEXT)
        nodes  (loop [nodes []]
                 (if-let [text-node (.nextNode walker)]
                   (recur (conj nodes text-node))
                   nodes))
        from   (text-position nodes start :start)
        to     (text-position nodes (+ start (count text)) :end)]
    (when (and from to)
      (let [range (.createRange js/document)]
        (.setStart range (first from) (second from))
        (.setEnd range (first to) (second to))
        range))))

(defn- browser-caret
  [^js node]
  (let [selection (.getSelection js/window)]
    (when (and selection (.contains node (.-focusNode selection)))
      (let [range (.createRange js/document)]
        (.setStart range (.-focusNode selection) (.-focusOffset selection))
        (.collapse range true)
        (dom-rect (.getBoundingClientRect range))))))

(defn- snapshot
  "All bounds use viewport CSS pixels, matching the fixed debug overlay."
  [^js node {:keys [active text] :as composition}]
  (let [matrix (some-> (.closest node "g.text-editor") (.getScreenCTM))
        caret  (text-editor/text-editor-get-cursor-rect)
        range  (when (and active (seq text)) (composition-range node composition))]
    {:platform cf/platform
     :browser cf/browser
     :writing-mode (.-writingMode (.getComputedStyle js/window node))
     :active active
     :text text
     :start (:start composition)
     :scroll {:x (.-scrollLeft node) :y (.-scrollTop node)}
     :wasm-caret (when (and matrix caret) (screen-rect caret matrix))
     :dom-caret (browser-caret node)
     :composition (when range (mapv dom-rect (array-seq (.getClientRects range))))}))

(defn- format-rect
  [{:keys [x y width height] :as rect}]
  (if rect
    (str "x=" (.toFixed x 1) " y=" (.toFixed y 1)
         " w=" (.toFixed width 2) " h=" (.toFixed height 2))
    "unavailable"))

(mf/defc bounds*
  {::mf/private true}
  [{:keys [rect class]}]
  (let [{:keys [x y width height]} rect]
    [:g {:class class}
     ;; Keep zero-size bounds visible without changing the measured values.
     [:rect {:x x :y y :width (max 1 width) :height (max 1 height)
             :fill "none" :stroke "currentColor" :stroke-width 1}]
     [:path {:d (str "M " (- x 4) " " y " h 8 M " x " " (- y 4) " v 8")
             :stroke "currentColor" :stroke-width 1}]]))

(mf/defc overlay*
  [{:keys [input-ref composing-ref composition-text-ref composition-base-ref]}]
  (let [options  (mf/deref dbg/state)
        enabled  (contains? options :ime)
        sample*  (mf/use-state nil)]
    ;; A separate component owns debug state. Sampling never changes the input,
    ;; its selection, or the editor component's state during composition.
    (mf/use-effect
     (mf/deps enabled)
     (fn []
       (when enabled
         (let [frame (atom nil)]
           (letfn [(tick []
                     (when-let [node (mf/ref-val input-ref)]
                       (let [composition {:active (mf/ref-val composing-ref)
                                          :text (mf/ref-val composition-text-ref)
                                          :start (mf/ref-val composition-base-ref)}
                             next-sample (snapshot node composition)]
                         (swap! sample* #(if (= % next-sample) % next-sample))))
                     (reset! frame (js/requestAnimationFrame tick)))]
             (tick)
             (fn [] (js/cancelAnimationFrame @frame)))))))

    (when enabled
      (let [{:keys [platform browser writing-mode active text start scroll
                    wasm-caret dom-caret composition]} @sample*]
        ;; A body portal avoids the text shape's SVG clip and transforms.
        (mf/portal
         (mf/html
          [:div {:class (stl/css :ime-debug) :data-testid "ime-debug" :aria-hidden true}
           [:svg {:class (stl/css :geometry)}
            (when wasm-caret
              [:> bounds* {:rect wasm-caret :class (stl/css :wasm-caret)}])
            (when dom-caret
              [:> bounds* {:rect dom-caret :class (stl/css :dom-caret)}])
            (for [[index rect] (map-indexed vector composition)]
              [:> bounds* {:key index :rect rect :class (stl/css :composition)}])]
           [:div {:class (stl/css :panel)}
            [:div (str "IME · " (name (or platform cf/platform))
                       " · " (name (or browser cf/browser)) " · " writing-mode
                       " · " (if active "composing" "idle"))]
            [:div {:class (stl/css :wasm-caret)} (str "Penpot caret: " (format-rect wasm-caret))]
            [:div {:class (stl/css :dom-caret)} (str "Browser caret: " (format-rect dom-caret))]
            [:div {:class (stl/css :composition)}
             (str "Browser composition: " (format-rect (first composition))
                  " · " (count composition) " rectangle(s)")]
            [:div (str "Input scroll: " (:x scroll) ", " (:y scroll)
                       " · composition start: " start " UTF-16 units")]
            (when active
              [:div {:class (stl/css :composition-text)} (str "Text: " text)])
            [:div {:class (stl/css :note)}
             "Bounds are in viewport CSS pixels. Native candidate window bounds are unavailable."]]])
         (.-body js/document))))))

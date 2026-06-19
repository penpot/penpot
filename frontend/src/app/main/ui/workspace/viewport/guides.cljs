;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.viewport.guides
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.types.color :as colors]
   [app.common.types.shape-tree :as ctst]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.context :as ctx]
   [app.main.ui.css-cursors :as cur]
   [app.main.ui.formats :as fmt]
   [app.main.ui.workspace.viewport.rulers :as rulers]
   [app.main.ui.workspace.viewport.viewport-ref :as uwvv]
   [app.render-wasm.api :as wasm.api]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:const guide-width 1)
(def ^:const guide-opacity 0.7)
(def ^:const guide-opacity-hover 1)
(def ^:const default-guide-color colors/new-danger)

(def ^:const guide-pill-width 34)
(def ^:const guide-pill-height 20)
(def ^:const guide-pill-corner-radius 4)
(def ^:const guide-active-area 16)

;; Manhattan distance (in screen pixels) the pointer must travel after
;; pointerdown before we consider the interaction a drag. Below this we keep
;; the hover state so a click — including the clicks that make up a
;; double-click — doesn't flicker the overlay pill.
(def ^:const guide-drag-threshold 3)

(def ^:const guide-creation-margin-left 8)
(def ^:const guide-creation-margin-top 28)
(def ^:const guide-creation-width 16)
(def ^:const guide-creation-height 24)

(defn compute-guide-drag-position
  "Computes the guide axis position from pointer drag delta."
  [{:keys [axis position start-pos start-pt current-pt zoom snap-pixel?]}]
  (let [delta        (/ (- (get current-pt axis) (get start-pt axis)) zoom)
        new-position (if (some? position)
                       (+ position delta)
                       (+ start-pos delta))]
    (if snap-pixel?
      (mth/round new-position)
      new-position)))

(defn guide-visible-in-focus?
  "When focus mode is active, only free guides and guides bound to a focused
  board are visible and interactive."
  [focus frame-id]
  (or (nil? frame-id)
      (empty? focus)
      (contains? focus frame-id)))

(defn wasm-visible-guides
  "Guide map sent to the WASM renderer. Must be the same map used to resolve
  `find-guide-at` indices (`guide-by-serialized-index`). Filters by
  rulers/grids visibility, focus mode, and excludes the guide currently being
  dragged (the SVG overlay draws it instead)."
  [{:keys [guides visible? focused dragging-id]}]
  (let [guides (if visible? (or guides {}) {})
        guides (if (seq focused)
                 (into {} (filter (fn [[_ guide]]
                                    (guide-visible-in-focus? focused (:frame-id guide)))
                                  guides))
                 guides)]
    (cond-> guides
      dragging-id (dissoc dragging-id))))

(defn use-guide
  "Hooks to support drag/drop for existing guides and new guides"
  [on-guide-change get-hover-frame zoom {:keys [id position axis frame-id]}]
  (let [dragging-ref  (mf/use-ref false)
        start-ref     (mf/use-ref nil)
        start-pos-ref (mf/use-ref nil)
        state         (mf/use-state
                       #(do {:hover false
                             :new-position nil
                             :new-frame-id frame-id}))

        frame-id
        (:new-frame-id @state)

        frame-ref
        (mf/with-memo [frame-id]
          (refs/object-by-id frame-id))

        frame
        (mf/deref frame-ref)

        snap-pixel?
        (mf/deref refs/snap-pixel?)

        read-only?
        (mf/use-ctx ctx/workspace-read-only?)

        on-pointer-enter
        (mf/use-fn
         (mf/deps read-only?)
         (fn []
           (when-not read-only?
             (st/emit! (dw/set-hover-guide id true))
             (swap! state assoc :hover true))))

        on-pointer-leave
        (mf/use-fn
         (mf/deps read-only?)
         (fn []
           (when-not read-only?
             (st/emit! (dw/set-hover-guide id false))
             (swap! state assoc :hover false))))

        on-pointer-down
        (mf/use-fn
         (mf/deps read-only?)
         (fn [event]
           (when-not read-only?
             (when (= 0 (.-button event))
               (dom/capture-pointer event)
               (mf/set-ref-val! dragging-ref true)
               (mf/set-ref-val! start-ref (dom/get-client-position event))
               (mf/set-ref-val! start-pos-ref (get @ms/mouse-position axis))))))

        on-pointer-up
        (mf/use-fn
         (mf/deps (select-keys @state [:new-position :new-frame-id]) on-guide-change read-only?)
         (fn []
           (when-not read-only?
             (when (some? on-guide-change)
               (when (some? (:new-position @state))
                 (on-guide-change {:position (:new-position @state)
                                   :frame-id (:new-frame-id @state)}))))))

        on-lost-pointer-capture
        (mf/use-fn
         (mf/deps read-only?)
         (fn [event]
           (when-not read-only?
             (dom/release-pointer event)
             (mf/set-ref-val! dragging-ref false)
             (mf/set-ref-val! start-ref nil)
             (mf/set-ref-val! start-pos-ref nil)
             (swap! state assoc :new-position nil))))

        on-pointer-move
        (mf/use-fn
         (mf/deps position zoom snap-pixel? read-only? get-hover-frame axis)
         (fn [event]
           (when-not read-only?
             (when (mf/ref-val dragging-ref)
               (let [start-pt     (mf/ref-val start-ref)
                     start-pos    (mf/ref-val start-pos-ref)
                     current-pt   (dom/get-client-position event)
                     new-position (compute-guide-drag-position
                                   {:axis axis
                                    :position position
                                    :start-pos start-pos
                                    :start-pt start-pt
                                    :current-pt current-pt
                                    :zoom zoom
                                    :snap-pixel? snap-pixel?})
                     new-frame-id (-> (get-hover-frame)
                                      (get :id))]

                 (swap! state assoc
                        :new-position new-position
                        :new-frame-id new-frame-id))))))]

    {:on-pointer-enter on-pointer-enter
     :on-pointer-leave on-pointer-leave
     :on-pointer-down on-pointer-down
     :on-pointer-up on-pointer-up
     :on-lost-pointer-capture on-lost-pointer-capture
     :on-pointer-move on-pointer-move
     :state state
     :frame frame}))

;; This functions are auxiliary to get the coords of components
;; depending on the axis we're handling

(defn guide-area-axis
  [pos vbox zoom frame axis]
  (let [rulers-pos (/ rulers/rulers-pos zoom)
        guide-active-area (/ guide-active-area zoom)]
    (cond
      (and (some? frame) (= axis :x))
      {:x (- pos (/ guide-active-area 2))
       :y (:y frame)
       :width guide-active-area
       :height (:height frame)}

      (some? frame)
      {:x (:x frame)
       :y (- pos (/ guide-active-area 2))
       :width (:width frame)
       :height guide-active-area}

      (= axis :x)
      {:x (- pos (/ guide-active-area 2))
       :y (+ (:y vbox) rulers-pos)
       :width guide-active-area
       :height (:height vbox)}

      :else
      {:x (+ (:x vbox) rulers-pos)
       :y (- pos (/ guide-active-area 2))
       :width (:width vbox)
       :height guide-active-area})))

(defn guide-line-axis
  ([pos vbox axis]
   (if (= axis :x)
     {:x1 pos
      :y1 (:y vbox)
      :x2 pos
      :y2 (+ (:y vbox) (:height vbox))}

     {:x1 (:x vbox)
      :y1 pos
      :x2 (+ (:x vbox) (:width vbox))
      :y2 pos}))

  ([pos vbox frame axis]
   (if (= axis :x)
     {:l1-x1 pos
      :l1-y1 (:y vbox)
      :l1-x2 pos
      :l1-y2 (:y frame)
      :l2-x1 pos
      :l2-y1 (:y frame)
      :l2-x2 pos
      :l2-y2 (+ (:y frame) (:height frame))
      :l3-x1 pos
      :l3-y1 (+ (:y frame) (:height frame))
      :l3-x2 pos
      :l3-y2 (+ (:y vbox) (:height vbox))}
     {:l1-x1 (:x vbox)
      :l1-y1 pos
      :l1-x2 (:x frame)
      :l1-y2 pos
      :l2-x1 (:x frame)
      :l2-y1 pos
      :l2-x2 (+ (:x frame) (:width frame))
      :l2-y2 pos
      :l3-x1 (+ (:x frame) (:width frame))
      :l3-y1 pos
      :l3-x2 (+ (:x vbox) (:width vbox))
      :l3-y2 pos})))

(defn guide-pill-axis
  [pos vbox zoom axis]
  (let [rulers-pos (/ rulers/rulers-pos zoom)
        guide-pill-width (/ guide-pill-width zoom)
        guide-pill-height (/ guide-pill-height zoom)]

    (if (= axis :x)
      {:rect-x      (- pos (/ guide-pill-width 2))
       :rect-y      (+ (:y vbox) rulers-pos (- (/ guide-pill-width 2)) (/ 3 zoom))
       :rect-width  guide-pill-width
       :rect-height guide-pill-height
       :text-x      pos
       :text-y      (+ (:y vbox) rulers-pos (- (/ 3 zoom)))}

      {:rect-x      (+ (:x vbox) rulers-pos (- (/ guide-pill-height 2)) (- (/ 4 zoom)))
       :rect-y      (- pos (/ guide-pill-width 2))
       :rect-width  guide-pill-height
       :rect-height guide-pill-width
       :text-x      (+ (:x vbox) rulers-pos (- (/ 3 zoom)))
       :text-y      pos})))

(defn guide-inside-vbox?
  ([zoom vbox]
   (partial guide-inside-vbox? zoom vbox))

  ([zoom {:keys [x y width height]} {:keys [axis position]}]
   (let [rule-area-size (/ rulers/ruler-area-size zoom)
         x1 x
         x2 (+ x width)
         y1 y
         y2 (+ y height)]
     (if (= axis :x)
       (and (>= position (+ x1 rule-area-size))
            (<= position x2))
       (and (>= position (+ y1 rule-area-size))
            (<= position y2))))))

(defn guide-creation-area
  [vbox zoom axis]
  (if (= axis :x)
    {:x (+ (:x vbox) (/ guide-creation-margin-left zoom))
     :y (:y vbox)
     :width (/ guide-creation-width zoom)
     :height (:height vbox)}

    {:x (+ (:x vbox) (/ guide-creation-margin-top zoom))
     :y (:y vbox)
     :width (:width vbox)
     :height (/ guide-creation-height zoom)}))

(defn is-guide-inside-frame?
  [guide frame]

  (if (= :x (:axis guide))
    (and (>= (:position guide) (:x frame))
         (<= (:position guide) (+ (:x frame) (:width frame))))

    (and (>= (:position guide) (:y frame))
         (<= (:position guide) (+ (:y frame) (:height frame))))))

(mf/defc guide-pill*
  "Presentational pill shown next to a guide line: a colored rounded rect with
  either the guide position as text or, when `editing`, an inline number input.
  Shared by the SVG (`guide*`) and WASM overlay (`guide-overlay*`) renderers;
  each owns its own interaction model and passes the relevant handlers in."
  {::mf/wrap [mf/memo]}
  [{:keys [pos vbox zoom axis color frame-offset editing
           input-ref on-input-key-down on-input-blur on-double-click]}]
  (let [{:keys [rect-x rect-y rect-width rect-height text-x text-y]}
        (guide-pill-axis pos vbox zoom axis)
        corner-radius (/ guide-pill-corner-radius zoom)
        display-value (fmt/format-number (- pos frame-offset))
        input-w       (/ guide-pill-width zoom)
        input-h       (/ guide-pill-height zoom)]
    [:g.guide-pill
     [:rect {:x rect-x
             :y rect-y
             :width rect-width
             :height rect-height
             :rx corner-radius
             :ry corner-radius
             :style {:fill color}
             :on-double-click on-double-click}]

     (if editing
       [:foreignObject {:x (- text-x (/ input-w 2))
                        :y (- text-y (/ input-h 2))
                        :width input-w
                        :height input-h
                        :transform (when (= axis :y)
                                     (str "rotate(-90 " text-x "," text-y ")"))}
        [:input {:ref input-ref
                 :type "number"
                 :step "any"
                 :default-value display-value
                 :auto-focus true
                 :on-key-down on-input-key-down
                 :on-blur on-input-blur
                 :on-pointer-down dom/stop-propagation
                 :style {:width "100%"
                         :height "100%"
                         :border "none"
                         :outline "none"
                         :padding 0
                         :margin 0
                         :background "transparent"
                         :color colors/white
                         :font-family rulers/font-family
                         :font-size (str (/ rulers/font-size zoom) "px")
                         :text-align "center"
                         :-moz-appearance "textfield"}}]]
       [:text {:x text-x
               :y text-y
               :text-anchor "middle"
               :dominant-baseline "middle"
               :transform (when (= axis :y) (str "rotate(-90 " text-x "," text-y ")"))
               :style {:font-size (/ rulers/font-size zoom)
                       :font-family rulers/font-family
                       :fill colors/white
                       :pointer-events "none"}}
        display-value])]))

(mf/defc guide-line*
  "Presentational guide line. With a `frame`, draws the solid in-frame segment
  and, on `hover?`, the dotted out-of-frame extensions; without a frame, a
  single solid line. `show-main?` (default true) lets callers suppress the solid
  segment when the render engine already draws it — e.g. the WASM hover overlay,
  which only needs the dotted extensions on top of the WASM-rendered line."
  {::mf/wrap [mf/memo]}
  [{:keys [pos vbox zoom axis color frame hover? show-main?]
    :or {show-main? true}}]
  (let [width        (/ guide-width zoom)
        main-opacity (if hover? guide-opacity-hover guide-opacity)
        dash         (str "0, " (/ 6 zoom))]
    (if (some? frame)
      (let [{:keys [l1-x1 l1-y1 l1-x2 l1-y2
                    l2-x1 l2-y1 l2-x2 l2-y2
                    l3-x1 l3-y1 l3-x2 l3-y2]}
            (guide-line-axis pos vbox frame axis)]
        [:g
         (when hover?
           [:line {:x1 l1-x1
                   :y1 l1-y1
                   :x2 l1-x2
                   :y2 l1-y2
                   :style {:stroke color
                           :stroke-opacity guide-opacity-hover
                           :stroke-dasharray dash
                           :stroke-linecap "round"
                           :stroke-width width}}])
         (when show-main?
           [:line {:x1 l2-x1
                   :y1 l2-y1
                   :x2 l2-x2
                   :y2 l2-y2
                   :style {:stroke color
                           :stroke-width width
                           :stroke-opacity main-opacity}}])
         (when hover?
           [:line {:x1 l3-x1
                   :y1 l3-y1
                   :x2 l3-x2
                   :y2 l3-y2
                   :style {:stroke color
                           :stroke-opacity guide-opacity-hover
                           :stroke-width width
                           :stroke-dasharray dash
                           :stroke-linecap "round"}}])])

      (when show-main?
        (let [{:keys [x1 y1 x2 y2]} (guide-line-axis pos vbox axis)]
          [:line {:x1 x1
                  :y1 y1
                  :x2 x2
                  :y2 y2
                  :style {:stroke color
                          :stroke-width width
                          :stroke-opacity main-opacity}}])))))

(mf/defc guide*
  {::mf/wrap [mf/memo]}
  [{:keys [guide is-hover on-guide-change get-hover-frame vbox zoom
           hover-frame disabled-guides frame-modifier frame-transform
           on-guide-context-menu]}]
  (let [axis
        (get guide :axis)

        guide-color
        (or (:color guide) default-guide-color)

        read-only?
        (mf/use-ctx ctx/workspace-read-only?)

        is-editing*
        (mf/use-state false)

        is-editing
        (deref is-editing*)

        input-ref
        (mf/use-ref nil)

        handle-change-position
        (mf/use-fn
         (mf/deps on-guide-change guide)
         (fn [changes]
           (when on-guide-change
             (on-guide-change (merge guide changes)))))

        {:keys [on-pointer-enter
                on-pointer-leave
                on-pointer-down
                on-pointer-up
                on-lost-pointer-capture
                on-pointer-move
                state
                frame]}
        (use-guide handle-change-position get-hover-frame zoom guide)

        base-frame
        (or frame hover-frame)

        frame
        (cond-> base-frame
          (some? frame-modifier)
          (gsh/transform-shape frame-modifier)

          (some? frame-transform)
          (gsh/apply-transform frame-transform))

        move-vec
        (gpt/to-vec (gpt/point (:x base-frame) (:y base-frame))
                    (gpt/point (:x frame) (:y frame)))

        pos
        (+ (or (:new-position @state) (:position guide)) (get move-vec axis))

        frame-guide-outside?
        (and (some? frame)
             (not (is-guide-inside-frame? (assoc guide :position pos) frame)))

        frame-offset
        (if (some? frame)
          (if (= axis :x) (:x frame) (:y frame))
          0)

        accept-editing
        (mf/use-fn
         (mf/deps frame-offset on-guide-change guide)
         (fn []
           ;; Enter both fires this and triggers a blur that calls it again;
           ;; bail out on the second invocation when the input is already gone.
           (when-let [input (mf/ref-val input-ref)]
             (let [parsed (-> input dom/get-value str/trim d/parse-double)]
               (reset! is-editing* false)
               (when (and (some? parsed) (some? on-guide-change))
                 (on-guide-change (assoc guide :position (+ parsed frame-offset))))))))

        cancel-editing
        (mf/use-fn
         #(reset! is-editing* false))

        on-input-key-down
        (mf/use-fn
         (mf/deps accept-editing cancel-editing)
         (fn [event]
           (cond
             (kbd/enter? event)
             (do (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (accept-editing))

             (kbd/esc? event)
             (do (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (cancel-editing)))))

        on-double-click
        (mf/use-fn
         (mf/deps read-only?)
         (fn [event]
           (when-not read-only?
             (dom/stop-propagation event)
             (reset! is-editing* true))))]

    (mf/with-effect [is-editing]
      (when is-editing
        (some-> (mf/ref-val input-ref) dom/select-text!)))

    (when (or (nil? frame)
              (and (cfh/root-frame? frame)
                   (not (ctst/rotated-frame? frame))))
      [:g.guide-area {:opacity (when frame-guide-outside? 0)}
       (when-not disabled-guides
         (let [{:keys [x y width height]} (guide-area-axis pos vbox zoom frame axis)
               on-context-menu
               (fn [event]
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (when on-guide-context-menu
                   (on-guide-context-menu event guide)))]
           [:rect {:x x
                   :y y
                   :width width
                   :height height
                   :class (if (= axis :x) (cur/get-dynamic "resize-ew" 0) (cur/get-dynamic "resize-ns" 0))
                   :style {:fill "none"
                           :pointer-events (if frame-guide-outside? "none" "fill")}
                   :on-pointer-enter on-pointer-enter
                   :on-pointer-leave on-pointer-leave
                   :on-pointer-down on-pointer-down
                   :on-pointer-up on-pointer-up
                   :on-lost-pointer-capture on-lost-pointer-capture
                   :on-pointer-move on-pointer-move
                   :on-context-menu on-context-menu
                   :on-double-click on-double-click}]))

       [:> guide-line* {:pos pos
                        :vbox vbox
                        :zoom zoom
                        :axis axis
                        :color guide-color
                        :frame frame
                        :hover? (or is-hover (:hover @state))}]

       ;; If the guide is associated to a frame we show the position relative
       ;; to the frame (handled via `frame-offset` inside `guide-pill*`).
       (when (or is-hover (:hover @state) is-editing)
         [:> guide-pill* {:pos pos
                          :vbox vbox
                          :zoom zoom
                          :axis axis
                          :color guide-color
                          :frame-offset frame-offset
                          :editing is-editing
                          :input-ref input-ref
                          :on-input-key-down on-input-key-down
                          :on-input-blur accept-editing
                          :on-double-click on-double-click}])])))

(mf/defc new-guide-area*
  [{:keys [vbox zoom axis get-hover-frame disabled-guides]}]
  (let [on-guide-change
        (mf/use-fn
         (mf/deps vbox)
         (fn [guide]
           (let [guide (-> guide
                           (assoc :id (uuid/next))
                           (assoc :axis axis))]
             (when (guide-inside-vbox? zoom vbox guide)
               (st/emit! (dw/update-guides guide))))))

        {:keys [on-pointer-enter
                on-pointer-leave
                on-pointer-down
                on-pointer-up
                on-lost-pointer-capture
                on-pointer-move
                state
                frame]}
        (use-guide on-guide-change get-hover-frame zoom {:axis axis})

        read-only?
        (mf/use-ctx ctx/workspace-read-only?)]

    [:g.new-guides
     (when-not disabled-guides
       (let [{:keys [x y width height]} (guide-creation-area vbox zoom axis)]
         [:rect {:x x
                 :y y
                 :width width
                 :height height
                 :on-pointer-enter on-pointer-enter
                 :on-pointer-leave on-pointer-leave
                 :on-pointer-down on-pointer-down
                 :on-pointer-up on-pointer-up
                 :on-lost-pointer-capture on-lost-pointer-capture
                 :on-pointer-move on-pointer-move
                 :class (when-not read-only?
                          (if (= axis :x)
                            (cur/get-dynamic "resize-ew" 0)
                            (cur/get-dynamic "resize-ns" 0)))
                 :style {:fill "none"
                         :pointer-events "fill"}}]))

     (when (:new-position @state)
       [:> guide* {:guide {:axis axis :position (:new-position @state)}
                   :get-hover-frame get-hover-frame
                   :vbox vbox
                   :zoom zoom
                   :is-hover true
                   :hover-frame frame}])]))

(defn- guide-by-serialized-index
  "Maps a WASM guide index back to the guide map entry. `guides` must be the
  same map passed to `set-guides` (typically `wasm-visible-guides`); index
  order matches `write-guides` / `(vec (vals guides))`."
  [guides index]
  (when (>= index 0)
    (nth (vec (vals guides)) index nil)))

(mf/defc guide-overlay*
  "Temporary SVG rendering of a guide that's being interacted with (drag, hover
  or inline edit). In :hover mode the WASM engine still draws the (in-frame)
  line, so we only overlay the position pill plus, for frame-anchored guides,
  the dotted out-of-frame extensions. Drag and edit hide the WASM line and draw
  the full SVG line. In :edit mode the pill is an editable input."
  [{:keys [guide position vbox zoom mode frame frame-offset
           on-input-commit on-input-cancel]}]
  (let [axis         (:axis guide)
        guide-color  (or (:color guide) default-guide-color)
        input-ref    (mf/use-ref nil)
        ;; In :hover mode the WASM engine still renders the guide line, so we
        ;; only overlay the dotted extensions. Drag and edit hide the WASM line
        ;; and require the full SVG line.
        show-line?   (not= mode :hover)
        show-pill?   (or (= mode :edit) (= mode :hover))
        editing?     (= mode :edit)

        on-key-down
        (mf/use-fn
         (mf/deps on-input-commit on-input-cancel)
         (fn [event]
           (cond
             (kbd/enter? event)
             (do (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (on-input-commit (-> (mf/ref-val input-ref) dom/get-value)))

             (kbd/esc? event)
             (do (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (on-input-cancel)))))

        on-blur
        (mf/use-fn
         (mf/deps on-input-commit)
         (fn []
           (on-input-commit (-> (mf/ref-val input-ref) dom/get-value))))]

    (mf/with-effect [mode]
      (when editing?
        (some-> (mf/ref-val input-ref) dom/select-text!)))

    [:g.guide-overlay
     ;; Drag/edit: WASM hides its line, so draw the full SVG guide line.
     (when show-line?
       [:> guide-line* {:pos position
                        :vbox vbox
                        :zoom zoom
                        :axis axis
                        :color guide-color
                        :hover? true}])

     ;; Hover: WASM still draws the in-frame segment; only add the dotted
     ;; out-of-frame extensions for frame-anchored guides.
     (when (and (= mode :hover) (some? frame))
       [:> guide-line* {:pos position
                        :vbox vbox
                        :zoom zoom
                        :axis axis
                        :color guide-color
                        :frame frame
                        :hover? true
                        :show-main? false}])

     (when show-pill?
       [:> guide-pill* {:pos position
                        :vbox vbox
                        :zoom zoom
                        :axis axis
                        :color guide-color
                        :frame-offset frame-offset
                        :editing editing?
                        :input-ref input-ref
                        :on-input-key-down on-key-down
                        :on-input-blur on-blur}])]))

(defn use-wasm-guide-interaction
  "Owns both drag and inline-edit lifecycles for WASM-rendered guides.

  Returns a map with the live overlay `state` (`{:guide ... :new-position ...
  :mode :drag|:edit ...}` or nil) plus callbacks the overlay needs in edit
  mode: `commit-edit` (commits the parsed input value) and `cancel-edit`
  (drops the edit without committing)."
  [{:keys [wasm-guides zoom wasm-guides? disabled-guides? on-guide-change
           on-guide-drag on-guide-hover get-hover-frame focus]}]
  (let [dragging-ref       (mf/use-ref false)
        moved-ref          (mf/use-ref false)
        editing-ref        (mf/use-ref false)
        start-ref          (mf/use-ref nil)
        guide-ref          (mf/use-ref nil)
        pending-ref        (mf/use-ref nil)
        drag-listeners-ref (mf/use-ref nil)
        hover-axis-ref     (mf/use-ref nil)
        hover-guide-id-ref (mf/use-ref nil)
        state              (mf/use-state nil)

        snap-pixel?
        (mf/deref refs/snap-pixel?)

        read-only?
        (mf/use-ctx ctx/workspace-read-only?)

        ;; The handlers are defined here so they close directly over the refs and
        ;; the current render's props (guides, zoom, ...). The pointerdown /
        ;; dblclick listeners are re-registered by the effect below whenever those
        ;; props change, so they always see fresh values.
        remove-drag-listeners
        (fn []
          (when-let [{:keys [on-move on-up]} (mf/ref-val drag-listeners-ref)]
            (when-let [viewport @uwvv/viewport-ref]
              (.removeEventListener viewport "pointermove" on-move true)
              (.removeEventListener viewport "pointerup" on-up true)
              (.removeEventListener viewport "pointercancel" on-up true))
            (mf/set-ref-val! drag-listeners-ref nil)))

        emit-hover-axis
        (fn [axis]
          (when (not= axis (mf/ref-val hover-axis-ref))
            (mf/set-ref-val! hover-axis-ref axis)
            (when (some? on-guide-hover)
              (on-guide-hover axis))))

        ;; Mirrors what the SVG renderer does on pointer-enter / -leave:
        ;; populates `[:workspace-guides :hover]` so the Del / Backspace
        ;; shortcut (`dw/delete-selected`) can remove the hovered guide.
        emit-hover-guide-id
        (fn [id]
          (let [prev (mf/ref-val hover-guide-id-ref)]
            (when (not= id prev)
              (mf/set-ref-val! hover-guide-id-ref id)
              (when prev
                (st/emit! (dw/set-hover-guide prev false)))
              (when id
                (st/emit! (dw/set-hover-guide id true))))))

        clear-drag-refs
        (fn []
          (remove-drag-listeners)
          (mf/set-ref-val! dragging-ref false)
          (mf/set-ref-val! moved-ref false)
          (mf/set-ref-val! start-ref nil)
          (mf/set-ref-val! guide-ref nil)
          (mf/set-ref-val! pending-ref nil))

        reset-state
        (fn []
          (clear-drag-refs)
          (mf/set-ref-val! editing-ref false)
          (when (some? on-guide-drag)
            (on-guide-drag nil))
          (emit-hover-axis nil)
          (emit-hover-guide-id nil)
          (reset! state nil))

        finish-drag
        (fn [event]
          (when (mf/ref-val dragging-ref)
            (let [moved? (mf/ref-val moved-ref)]
              (when (and moved? (some? on-guide-change))
                (when-let [{:keys [guide new-position new-frame-id]}
                           (mf/ref-val pending-ref)]
                  (when (and (some? guide) (some? new-position))
                    (on-guide-change (assoc guide
                                            :position new-position
                                            :frame-id new-frame-id)))))
              (when-let [viewport @uwvv/viewport-ref]
                (when (.-pointerId event)
                  (.releasePointerCapture viewport (.-pointerId event))))
              ;; A click without movement (no drag): leave the hover state
              ;; alone so a follow-up double-click can transition straight
              ;; from :hover to :edit without flickering through nil.
              (if moved?
                (reset-state)
                (clear-drag-refs)))))

        drag-move
        (fn [move-event]
          (when (mf/ref-val dragging-ref)
            (when-let [guide (mf/ref-val guide-ref)]
              (let [start-pt   (mf/ref-val start-ref)
                    current-pt (dom/get-client-position move-event)
                    already-moved? (mf/ref-val moved-ref)
                    past-threshold?
                    (or already-moved?
                        (> (+ (mth/abs (- (:x current-pt) (:x start-pt)))
                              (mth/abs (- (:y current-pt) (:y start-pt))))
                           guide-drag-threshold))]
                (when past-threshold?
                  (let [axis         (:axis guide)
                        new-position (compute-guide-drag-position
                                      {:axis axis
                                       :position (:position guide)
                                       :start-pt start-pt
                                       :current-pt current-pt
                                       :zoom zoom
                                       :snap-pixel? snap-pixel?})
                        new-frame-id (-> (get-hover-frame) (get :id))
                        pending      {:guide guide
                                      :new-position new-position
                                      :new-frame-id new-frame-id
                                      :mode :drag}]
                    (when-not already-moved?
                      (mf/set-ref-val! moved-ref true)
                      (when (some? on-guide-drag)
                        (on-guide-drag (:id guide))))
                    (mf/set-ref-val! pending-ref pending)
                    (reset! state pending)))))))

        editing?
        (fn [] (mf/ref-val editing-ref))

        guide-at-event
        (fn [event]
          (when-let [pt (uwvv/point->viewport (dom/get-client-position event))]
            (guide-by-serialized-index wasm-guides (wasm.api/find-guide-at pt zoom))))

        visible-guide-at-event
        (fn [event]
          (when-let [guide (guide-at-event event)]
            (when (guide-visible-in-focus? focus (:frame-id guide))
              guide)))

        guide-frame-offset
        (fn [guide]
          (let [frame (some-> (:frame-id guide) refs/object-by-id deref)]
            (if frame
              (if (= :x (:axis guide)) (:x frame) (:y frame))
              0)))

        pointer-move-hover
        (fn [event]
          ;; Only update hover cursor / pill when we are not in the middle of
          ;; a drag or edit. During drag the cursor is already set to the
          ;; dragged guide's axis; during edit the input owns the cursor.
          (when (and (not read-only?)
                     (not (editing?))
                     (not (mf/ref-val dragging-ref)))
            (let [guide (visible-guide-at-event event)
                  current-state @state
                  current-hover-id (when (= :hover (:mode current-state))
                                     (-> current-state :guide :id))]
              (emit-hover-axis (:axis guide))
              (emit-hover-guide-id (:id guide))
              (cond
                (and (some? guide)
                     (not= (:id guide) current-hover-id))
                (let [frame (some-> (:frame-id guide) refs/object-by-id deref)]
                  (reset! state {:guide guide
                                 :new-position (:position guide)
                                 :frame-offset (guide-frame-offset guide)
                                 ;; Only root, non-rotated frames get the
                                 ;; segmented line (matching the SVG renderer),
                                 ;; so we only overlay dotted extensions there.
                                 :frame (when (and frame
                                                   (cfh/root-frame? frame)
                                                   (not (ctst/rotated-frame? frame)))
                                          frame)
                                 :mode :hover}))

                (and (nil? guide) (some? current-hover-id))
                (reset! state nil)))))

        pointer-down
        (fn [event]
          (when (and (not read-only?) (not (editing?)))
            ;; While editing, any click outside the input commits the edit
            ;; via the input's blur handler. Don't initiate a drag on the
            ;; same pointerdown.
            (when (= 0 (.-button event))
              (let [client-pos (dom/get-client-position event)
                    guide (visible-guide-at-event event)
                    {:keys [id axis position frame-id]} guide]
                (when guide
                  (when-let [viewport @uwvv/viewport-ref]
                    (.setPointerCapture viewport (.-pointerId event)))
                  (dom/stop-propagation event)
                  (emit-hover-axis axis)
                  (emit-hover-guide-id id)
                  (mf/set-ref-val! dragging-ref true)
                  (mf/set-ref-val! moved-ref false)
                  (mf/set-ref-val! start-ref client-pos)
                  (mf/set-ref-val! guide-ref guide)
                  (mf/set-ref-val! pending-ref
                                   {:guide guide
                                    :new-position position
                                    :new-frame-id frame-id
                                    :mode :drag})
                  ;; Pointer capture (above) routes all subsequent pointer
                  ;; events to the viewport, so we listen on the viewport
                  ;; itself rather than window. This keeps events flowing
                  ;; even outside the browser window.
                  (when-let [viewport @uwvv/viewport-ref]
                    (let [on-move #(drag-move %)
                          on-up   #(finish-drag %)]
                      (mf/set-ref-val! drag-listeners-ref
                                       {:on-move on-move :on-up on-up})
                      (.addEventListener viewport "pointermove" on-move true)
                      (.addEventListener viewport "pointerup" on-up true)
                      (.addEventListener viewport "pointercancel" on-up true))))))))

        double-click
        (fn [event]
          (when (and (not read-only?) (not (editing?)))
            (let [guide (visible-guide-at-event event)
                  {:keys [id axis position frame-id]} guide]
              (when guide
                (dom/prevent-default event)
                (dom/stop-propagation event)
                (when (some? on-guide-drag)
                  (on-guide-drag id))
                (mf/set-ref-val! guide-ref guide)
                (mf/set-ref-val! editing-ref true)
                (let [frame  (some-> frame-id refs/object-by-id deref)
                      offset (if frame
                               (if (= :x axis) (:x frame) (:y frame))
                               0)]
                  (reset! state {:guide guide
                                 :new-position position
                                 :new-frame-id frame-id
                                 :frame-offset offset
                                 :mode :edit}))))))

        commit-edit
        (fn [raw-value]
          (when (editing?)
            (let [{:keys [guide new-frame-id frame-offset]} @state
                  parsed (some-> raw-value str/trim d/parse-double)]
              (when (and (some? parsed) (some? on-guide-change))
                (on-guide-change (assoc guide
                                        :position (+ parsed frame-offset)
                                        :frame-id new-frame-id)))
              (reset-state))))

        cancel-edit
        (fn []
          (when (editing?)
            (reset-state)))]

    (mf/with-effect [wasm-guides? disabled-guides? read-only?
                     wasm-guides zoom focus snap-pixel?
                     on-guide-change on-guide-drag on-guide-hover get-hover-frame]
      (when (and wasm-guides? (not disabled-guides?) (not read-only?))
        (when-let [viewport @uwvv/viewport-ref]
          (.addEventListener viewport "pointerdown" pointer-down true)
          (.addEventListener viewport "pointermove" pointer-move-hover true)
          (.addEventListener viewport "dblclick" double-click true)
          (fn []
            (.removeEventListener viewport "pointerdown" pointer-down true)
            (.removeEventListener viewport "pointermove" pointer-move-hover true)
            (.removeEventListener viewport "dblclick" double-click true)
            ;; Only tear down state on real teardown. If this cleanup is
            ;; triggered by a dependency change mid-interaction, leave the
            ;; active drag/edit (and its listeners) untouched so it can
            ;; finish.
            (when-not (or (mf/ref-val dragging-ref) (editing?))
              (reset-state))))))

    {:state state
     :commit-edit commit-edit
     :cancel-edit cancel-edit}))

(mf/defc wasm-guide-overlay-layer*
  "Owns WASM guide drag/edit state and overlay rendering so updates are not
  blocked by memoization on `viewport-guides*`."
  [{:keys [wasm-guides zoom wasm-guides? disabled-guides? on-guide-change
           on-guide-drag on-guide-hover get-hover-frame focus vbox]}]
  (let [{:keys [state commit-edit cancel-edit]}
        (use-wasm-guide-interaction {:wasm-guides wasm-guides
                                     :zoom zoom
                                     :wasm-guides? wasm-guides?
                                     :disabled-guides? disabled-guides?
                                     :on-guide-change on-guide-change
                                     :on-guide-drag on-guide-drag
                                     :on-guide-hover on-guide-hover
                                     :get-hover-frame get-hover-frame
                                     :focus focus})

        {:keys [guide new-position mode frame frame-offset]} @state]

    (when (some? guide)
      [:> guide-overlay* {:guide guide
                          :position new-position
                          :vbox vbox
                          :zoom zoom
                          :mode mode
                          :frame frame
                          :frame-offset (or frame-offset 0)
                          :on-input-commit commit-edit
                          :on-input-cancel cancel-edit}])))

(mf/defc viewport-guides*
  {::mf/wrap [mf/memo]}
  [{:keys [zoom vbox hover-frame disabled-guides modifiers guides wasm-guides
           wasm-guides? on-guide-drag on-guide-hover]}]
  (let [visible-guides
        (mf/with-memo [guides vbox]
          (->> (vals guides)
               (filter (partial guide-inside-vbox? zoom vbox))))

        focus
        (mf/deref refs/workspace-focus-selected)

        hover-frame-ref
        (mf/use-ref nil)

        ;; We use the ref to not redraw every guide everytime the hovering frame change
        ;; we're only interested to get the frame in the guide we're moving
        get-hover-frame
        (mf/use-fn
         #(mf/ref-val hover-frame-ref))

        on-guide-change
        (mf/use-fn
         (mf/deps vbox)
         (fn [guide]
           (if (guide-inside-vbox? zoom vbox guide)
             (st/emit! (dw/update-guides guide))
             (st/emit! (dw/remove-guide guide)))))

        on-guide-context-menu
        (mf/use-fn
         (fn [event guide]
           (let [position (dom/get-client-position event)]
             (st/emit! (dw/show-guide-context-menu {:position position
                                                    :guide guide})))))

        ;; When guides are WASM-rendered, right-click hit testing is delegated to
        ;; the render engine instead of per-guide SVG areas.
        on-wasm-context-menu
        (mf/use-fn
         (mf/deps wasm-guides zoom disabled-guides)
         (fn [event]
           (when-not disabled-guides
             (let [position (dom/get-client-position event)
                   pt       (uwvv/point->viewport position)
                   index    (when pt (wasm.api/find-guide-at pt zoom))
                   guide    (guide-by-serialized-index wasm-guides index)]
               (when guide
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (st/emit! (dw/show-guide-context-menu {:position position
                                                        :guide guide})))))))

        frame-modifiers
        (-> (group-by :id modifiers)
            (update-vals (comp :transform first)))]

    (mf/with-effect [hover-frame]
      (mf/set-ref-val! hover-frame-ref hover-frame))

    (mf/with-effect [wasm-guides? disabled-guides on-wasm-context-menu]
      (when (and wasm-guides? (not disabled-guides))
        (when-let [viewport @uwvv/viewport-ref]
          (.addEventListener viewport "contextmenu" on-wasm-context-menu true)
          #(.removeEventListener viewport "contextmenu" on-wasm-context-menu true))))

    [:g.guides {:pointer-events "none"}
     [:> new-guide-area* {:vbox vbox
                          :zoom zoom
                          :axis :x
                          :get-hover-frame get-hover-frame
                          :disabled-guides disabled-guides}]

     [:> new-guide-area* {:vbox vbox
                          :zoom zoom
                          :axis :y
                          :get-hover-frame get-hover-frame
                          :disabled-guides disabled-guides}]

     (when wasm-guides?
       [:> wasm-guide-overlay-layer* {:wasm-guides wasm-guides
                                      :zoom zoom
                                      :wasm-guides? wasm-guides?
                                      :disabled-guides? disabled-guides
                                      :on-guide-change on-guide-change
                                      :on-guide-drag on-guide-drag
                                      :on-guide-hover on-guide-hover
                                      :get-hover-frame get-hover-frame
                                      :focus focus
                                      :vbox vbox}])

     (when-not wasm-guides?
       (for [{:keys [id frame-id] :as guide} visible-guides]
         (when (guide-visible-in-focus? focus frame-id)
           [:> guide* {:key (dm/str "guide-" id)
                       :guide guide
                       :vbox vbox
                       :zoom zoom
                       :frame-transform (get frame-modifiers frame-id)
                       :get-hover-frame get-hover-frame
                       :on-guide-change on-guide-change
                       :on-guide-context-menu on-guide-context-menu
                       :disabled-guides disabled-guides}])))]))

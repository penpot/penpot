; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.viewport
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.main.constants :as c]
   [app.main.data.colors :as dwc]
   [app.main.data.fetch :as mdf]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.drawing :as dd]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.context :as ctx]
   [app.main.ui.cursors :as cur]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as i]
   [app.main.ui.keyboard :as kbd]
   [app.main.ui.workspace.colorpicker.pixel-overlay :refer [pixel-overlay]]
   [app.main.ui.workspace.comments :refer [comments-layer]]
   [app.main.ui.workspace.drawarea :refer [draw-area]]
   [app.main.ui.workspace.frame-grid :refer [frame-grid]]
   [app.main.ui.workspace.gradients :refer [gradient-handlers]]
   [app.main.ui.workspace.presence :as presence]
   [app.main.ui.workspace.selection :refer [selection-handlers]]
   [app.main.ui.workspace.shapes :refer [shape-wrapper frame-wrapper]]
   [app.main.ui.workspace.shapes.interactions :refer [interactions]]
   [app.main.ui.workspace.shapes.outline :refer [outline]]
   [app.main.ui.workspace.shapes.path.actions :refer [path-actions]]
   [app.main.ui.workspace.snap-distances :refer [snap-distances]]
   [app.main.ui.workspace.snap-points :refer [snap-points]]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
   [app.util.http :as http]
   [app.util.object :as obj]
   [app.util.perf :as perf]
   [app.util.timers :as timers]
   [beicon.core :as rx]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [goog.events :as events]
   [potok.core :as ptk]
   [promesa.core :as p]
   [rumext.alpha :as mf])
  (:import goog.events.EventType
           goog.events.WheelEvent))

(defonce css-mouse?
  (cfg/check-browser? :firefox))

(defn get-cursor [cursor]
  (if-not css-mouse?
    (name cursor)

    (case cursor
      :hand cur/hand
      :comments cur/comments
      :create-artboard cur/create-artboard
      :create-rectangle cur/create-rectangle
      :create-ellipse cur/create-ellipse
      :pen cur/pen
      :pencil cur/pencil
      :create-shape cur/create-shape
      :duplicate cur/duplicate
      cur/pointer-inner)))

;; --- Coordinates Widget

(mf/defc coordinates
  []
  (let [coords (hooks/use-rxsub ms/mouse-position)]
    [:ul.coordinates
     [:span {:alt "x"}
      (str "X: " (:x coords "-"))]
     [:span {:alt "y"}
      (str "Y: " (:y coords "-"))]]))

(mf/defc cursor-tooltip
  [{:keys [zoom tooltip] :as props}]
  (let [coords (some-> (hooks/use-rxsub ms/mouse-position)
                       (gpt/divide (gpt/point zoom zoom)))
        pos-x (- (:x coords) 100)
        pos-y (+ (:y coords) 30)]
    [:g {:transform (str "translate(" pos-x "," pos-y ")")}
     [:foreignObject {:width 200 :height 100 :style {:text-align "center"}}
      [:span tooltip]]]))

;; --- Cursor tooltip

(defn- get-shape-tooltip
  "Return the shape tooltip text"
  [shape]
  (case (:type shape)
    :icon "Click to place the Icon"
    :image "Click to place the Image"
    :rect "Drag to draw a Box"
    :text "Drag to draw a Text Box"
    :path "Click to draw a Path"
    :circle "Drag to draw a Circle"
    nil))

;; --- Selection Rect

(mf/defc selection-rect
  {:wrap [mf/memo]}
  [{:keys [data] :as props}]
  (when data
    [:rect.selection-rect
     {:x (:x data)
      :y (:y data)
      :width (:width data)
      :height (:height data)}]))

;; --- Viewport Positioning

(defn- handle-viewport-positioning
  [viewport-ref]
  (let [node   (mf/ref-val viewport-ref)
        stoper (rx/filter #(= ::finish-positioning %) st/stream)

        stream (->> ms/mouse-position-delta
                    (rx/take-until stoper))]
    (st/emit! dw/start-pan)
    (rx/subscribe stream
                  (fn [delta]
                    (let [zoom (gpt/point @refs/selected-zoom)
                          delta (gpt/divide delta zoom)]
                      (st/emit! (dw/update-viewport-position
                                 {:x #(- % (:x delta))
                                  :y #(- % (:y delta))})))))))

;; --- Viewport

(declare remote-user-cursors)

(mf/defc render-cursor
  {::mf/wrap-props false}
  [props]
  (let [cursor (unchecked-get props "cursor")
        viewport (unchecked-get props "viewport")

        visible? (mf/use-state true)
        in-viewport? (mf/use-state true)

        cursor-ref (mf/use-ref nil)

        node (mf/ref-val cursor-ref)

        on-mouse-move
        (mf/use-callback
         (mf/deps node @visible?)
         (fn [left top event]

           (let [target (dom/get-target event)
                 style (.getComputedStyle js/window target)
                 cursor (.getPropertyValue style "cursor")

                 x (- (.-clientX event) left)
                 y (- (.-clientY event) top)]

             (cond
               (and (= cursor "none") (not @visible?))
               (reset! visible? true)

               (and (not= cursor "none") @visible?)
               (reset! visible? false))

             (timers/raf
              #(let [style (obj/get node "style")]
                 (obj/set! style "transform" (str "translate(" x "px, " y "px)")))))))]

    (mf/use-layout-effect
     (mf/deps on-mouse-move)
     (fn []
       (when viewport
         (let [{:keys [left top]} (dom/get-bounding-rect viewport)
               keys [(events/listen (dom/get-root) EventType.MOUSEMOVE (partial on-mouse-move left top))
                     (events/listen viewport EventType.POINTERENTER #(reset! in-viewport? true))
                     (events/listen viewport EventType.POINTERLEAVE #(reset! in-viewport? false))]]

           (fn []
             (doseq [key keys]
               (events/unlistenByKey key)))))))

    [:svg {:ref cursor-ref
           :width 20
           :height 20
           :viewBox "0 0 16 18"
           :style {:position "absolute"
                   :pointer-events "none"
                   :will-change "transform"
                   :display (when-not (and @in-viewport? @visible?) "none")}}
     [:use {:xlinkHref (str "#cursor-" cursor)}]]))

;; TODO: revisit the refs usage (vs props)
(mf/defc shape-outlines
  {::mf/wrap-props false}
  [props]
  (let [objects   (unchecked-get props "objects")
        selected  (or (unchecked-get props "selected") #{})
        hover     (or (unchecked-get props "hover") #{})
        edition   (unchecked-get props "edition")
        outline?  (set/union selected hover)
        show-outline? (fn [shape] (and (not (:hidden shape))
                                       (not (:blocked shape))
                                       (not= edition (:id shape))
                                       (outline? (:id shape))))
        shapes    (->> (vals objects) (filter show-outline?))
        transform (mf/deref refs/current-transform)
        color (if (or (> (count shapes) 1) (nil? (:shape-ref (first shapes))))
                "#31EFB8"
                "#00E0FF")]
    (when (nil? transform)
      [:g.outlines
       (for [shape shapes]
         [:& outline {:key (str "outline-" (:id shape))
                      :shape (gsh/transform-shape shape)
                      :color color}])])))

(mf/defc frames
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [props]
  (let [hover    (unchecked-get props "hover")
        selected (unchecked-get props "selected")
        ids      (unchecked-get props "ids")
        edition  (unchecked-get props "edition")
        data     (mf/deref refs/workspace-page)
        objects  (:objects data)
        root     (get objects uuid/zero)
        shapes   (->> (:shapes root)
                      (map #(get objects %)))

        shapes (if ids
                 (->> ids (map #(get objects %)))
                 shapes)]

    [:*
     [:g.shapes
      (for [item shapes]
        (if (= (:type item) :frame)
          [:& frame-wrapper {:shape item
                             :key (:id item)
                             :objects objects}]
          [:& shape-wrapper {:shape item
                             :key (:id item)}]))]

     [:& shape-outlines {:objects objects
                         :selected selected
                         :hover hover
                         :edition edition}]]))

(mf/defc ghost-frames
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [props]
  (let [modifiers    (obj/get props "modifiers")
        selected     (obj/get props "selected")

        sobjects     (mf/deref refs/selected-objects)
        selrect-orig (gsh/selection-rect sobjects)

        xf           (comp
                      (map #(assoc % :modifiers modifiers))
                      (map gsh/transform-shape))

        selrect      (->> (into [] xf sobjects)
                          (gsh/selection-rect))

        transform (when (and (mth/finite? (:x selrect-orig))
                             (mth/finite? (:y selrect-orig)))
                    (str/fmt "translate(%s,%s)" (- (:x selrect-orig)) (- (:y selrect-orig))))]
    [:& (mf/provider ctx/ghost-ctx) {:value true}
     [:svg.ghost
      {:x (mth/finite (:x selrect) 0)
       :y (mth/finite (:y selrect) 0)
       :width (mth/finite (:width selrect) 100)
       :height (mth/finite (:height selrect) 100)
       :style {:pointer-events "none"}}

      [:g {:transform transform}
       [:& frames
        {:ids selected}]]]]))

(defn format-viewbox [vbox]
  (str/join " " [(+ (:x vbox 0) (:left-offset vbox 0))
                 (:y vbox 0)
                 (:width vbox 0)
                 (:height vbox 0)]))

(mf/defc viewport
  [{:keys [local layout file] :as props}]
  (let [;; When adding data from workspace-local revisit `app.main.ui.workspace` to check
        ;; that the new parameter is sent
        {:keys [options-mode
                zoom
                vport
                vbox
                edition
                edit-path
                tooltip
                selected
                panning
                picking-color?
                transform
                hover
                modifiers
                selrect]} local

        page-id       (mf/use-ctx ctx/current-page-id)

        selected-objects (mf/deref refs/selected-objects)

        alt?          (mf/use-state false)
        cursor        (mf/use-state (get-cursor :pointer-inner))

        viewport-ref  (mf/use-ref nil)
        viewport-node (mf/use-state nil)

        zoom-view-ref (mf/use-ref nil)
        last-position (mf/use-var nil)
        disable-paste (mf/use-var false)
        in-viewport?  (mf/use-var false)

        drawing       (mf/deref refs/workspace-drawing)
        drawing-tool  (:tool drawing)
        drawing-obj   (:object drawing)
        drawing-path? (and edition (= :draw (get-in edit-path [edition :edit-mode])))
        zoom          (or zoom 1)

        show-grids?          (contains? layout :display-grid)
        show-snap-points?    (and (contains? layout :dynamic-alignment)
                                  (or drawing-obj transform))
        show-snap-distance?  (and (contains? layout :dynamic-alignment)
                                  (= transform :move)
                                  (not (empty? selected)))

        on-mouse-down
        (mf/use-callback
         (mf/deps drawing-tool edition)
         (fn [event]
           (dom/stop-propagation event)
           (let [event (.-nativeEvent event)
                 ctrl? (kbd/ctrl? event)
                 shift? (kbd/shift? event)
                 alt? (kbd/alt? event)]
             (when (= 1 (.-which event))
               (st/emit! (ms/->MouseEvent :down ctrl? shift? alt?)))

             (cond
               (and (= 1 (.-which event)) (not edition))
               (if drawing-tool
                 (when (not (#{:comments :path} drawing-tool))
                   (st/emit! (dd/start-drawing drawing-tool)))
                 (st/emit! (dw/handle-selection shift?)))

               (and (= 2 (.-which event)))
               (handle-viewport-positioning viewport-ref)))))

        on-context-menu
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (let [position (dom/get-client-position event)]
             (st/emit! (dw/show-context-menu {:position position})))))

        on-mouse-up
        (mf/use-callback
         (fn [event]
           (dom/stop-propagation event)
           (let [event (.-nativeEvent event)
                 ctrl? (kbd/ctrl? event)
                 shift? (kbd/shift? event)
                 alt? (kbd/alt? event)]
             (when (= 1 (.-which event))
               (st/emit! (ms/->MouseEvent :up ctrl? shift? alt?)))

             (when (= 2 (.-which event))
               (do
                 (dom/prevent-default event)

                 ;; We store this so in Firefox the middle button won't do a paste of the content
                 (reset! disable-paste true)
                 (timers/schedule #(reset! disable-paste false))
                 (st/emit! dw/finish-pan
                           ::finish-positioning))))))

        on-pointer-down
        (mf/use-callback
         (fn [event]
           (let [target (dom/get-target event)]
                                        ; Capture mouse pointer to detect the movements even if cursor
                                        ; leaves the viewport or the browser itself
                                        ; https://developer.mozilla.org/en-US/docs/Web/API/Element/setPointerCapture
             (.setPointerCapture target (.-pointerId event)))))

        on-pointer-up
        (mf/use-callback
         (fn [event]
           (let [target (dom/get-target event)]
                                        ; Release pointer on mouse up
             (.releasePointerCapture target (.-pointerId event)))))

        on-click
        (mf/use-callback
         (fn [event]
           (let [ctrl? (kbd/ctrl? event)
                 shift? (kbd/shift? event)
                 alt? (kbd/alt? event)]
             (if ctrl?
               (st/emit! (dw/select-last-layer @ms/mouse-position))
               (st/emit! (ms/->MouseEvent :click ctrl? shift? alt?))))))

        on-double-click
        (mf/use-callback
         (mf/deps drawing-path?)
         (fn [event]
           (dom/stop-propagation event)
           (let [ctrl? (kbd/ctrl? event)
                 shift? (kbd/shift? event)
                 alt? (kbd/alt? event)]
             (st/emit! (ms/->MouseEvent :double-click ctrl? shift? alt?)))))

        on-key-down
        (mf/use-callback
         (fn [event]
           (let [bevent (.getBrowserEvent ^js event)
                 key (.-keyCode ^js event)
                 ctrl? (kbd/ctrl? event)
                 shift? (kbd/shift? event)
                 alt? (kbd/alt? event)
                 target (dom/get-target event)]

             (when-not (.-repeat bevent)
               (st/emit! (ms/->KeyboardEvent :down key ctrl? shift? alt?))
               (when (and (kbd/space? event)
                          (not= "rich-text" (obj/get target "className"))
                          (not= "INPUT" (obj/get target "tagName"))
                          (not= "TEXTAREA" (obj/get target "tagName")))
                 (handle-viewport-positioning viewport-ref))))))

        on-key-up
        (mf/use-callback
         (fn [event]
           (let [key (.-keyCode event)
                 ctrl? (kbd/ctrl? event)
                 shift? (kbd/shift? event)
                 alt? (kbd/alt? event)]
             (when (kbd/space? event)
               (st/emit! dw/finish-pan ::finish-positioning))
             (st/emit! (ms/->KeyboardEvent :up key ctrl? shift? alt?)))))

        translate-point-to-viewport
        (mf/use-callback
         (fn [pt]
           (let [viewport (mf/ref-val viewport-ref)
                 vbox     (.. ^js viewport -viewBox -baseVal)
                 brect    (.getBoundingClientRect viewport)
                 brect    (gpt/point (d/parse-integer (.-left brect))
                                     (d/parse-integer (.-top brect)))
                 box      (gpt/point (.-x vbox)
                                     (.-y vbox))
                 ]
             (-> (gpt/subtract pt brect)
                 (gpt/divide (gpt/point @refs/selected-zoom))
                 (gpt/add box)
                 (gpt/round 0)))))

        on-mouse-move
        (mf/use-callback
         (fn [event]
           (let [event  (.getBrowserEvent ^js event)
                 raw-pt (dom/get-client-position event)
                 pt     (translate-point-to-viewport raw-pt)

                 ;; We calculate the delta because Safari's MouseEvent.movementX/Y drop
                 ;; events
                 delta (if @last-position
                         (gpt/subtract raw-pt @last-position)
                         (gpt/point 0 0))]
             (reset! last-position raw-pt)
             (st/emit! (ms/->PointerEvent :delta delta
                                          (kbd/ctrl? event)
                                          (kbd/shift? event)
                                          (kbd/alt? event)))
             (st/emit! (ms/->PointerEvent :viewport pt
                                          (kbd/ctrl? event)
                                          (kbd/shift? event)
                                          (kbd/alt? event))))))

        on-mouse-wheel
        (mf/use-callback
         (fn [event]
           (let [node (mf/ref-val viewport-ref)
                 target (dom/get-target event)]
             (cond
               (or (kbd/ctrl? event) (kbd/meta? event))
               (let [event (.getBrowserEvent ^js event)
                     pos   @ms/mouse-position]
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (let [delta (+ (.-deltaY ^js event)
                                (.-deltaX ^js event))]
                   (if (pos? delta)
                     (st/emit! (dw/decrease-zoom pos))
                     (st/emit! (dw/increase-zoom pos)))))

               (.contains ^js node target)
               (let [event (.getBrowserEvent ^js event)
                     delta-mode (.-deltaMode ^js event)

                     unit (cond
                            (= delta-mode WheelEvent.DeltaMode.PIXEL) 1
                            (= delta-mode WheelEvent.DeltaMode.LINE) 16
                            (= delta-mode WheelEvent.DeltaMode.PAGE) 100)

                     delta-y (-> (.-deltaY ^js event)
                                 (* unit)
                                 (/ @refs/selected-zoom))
                     delta-x (-> (.-deltaX ^js event)
                                 (* unit)
                                 (/ @refs/selected-zoom))]
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (if (kbd/shift? event)
                   (st/emit! (dw/update-viewport-position {:x #(+ % delta-y)}))
                   (st/emit! (dw/update-viewport-position {:x #(+ % delta-x)
                                                           :y #(+ % delta-y)}))))))))

        on-drag-enter
        (mf/use-callback
         (fn [e]
           (when (or (dnd/has-type? e "app/shape")
                     (dnd/has-type? e "app/component")
                     (dnd/has-type? e "Files")
                     (dnd/has-type? e "text/uri-list")
                     (dnd/has-type? e "text/asset-id"))
             (dom/prevent-default e))))

        on-drag-over
        (mf/use-callback
         (fn [e]
           (when (or (dnd/has-type? e "app/shape")
                     (dnd/has-type? e "app/component")
                     (dnd/has-type? e "Files")
                     (dnd/has-type? e "text/uri-list")
                     (dnd/has-type? e "text/asset-id"))
             (dom/prevent-default e))))

        on-image-uploaded
        (mf/use-callback
         (fn [image {:keys [x y]}]
           (st/emit! (dw/image-uploaded image x y))))

        on-drop
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (let [point (gpt/point (.-clientX event) (.-clientY event))
                 viewport-coord (translate-point-to-viewport point)
                 asset-id     (-> (dnd/get-data event "text/asset-id") uuid/uuid)
                 asset-name   (dnd/get-data event "text/asset-name")
                 asset-type   (dnd/get-data event "text/asset-type")]
             (cond
               (dnd/has-type? event "app/shape")
               (let [shape (dnd/get-data event "app/shape")
                     final-x (- (:x viewport-coord) (/ (:width shape) 2))
                     final-y (- (:y viewport-coord) (/ (:height shape) 2))]
                 (st/emit! (dw/add-shape (-> shape
                                             (assoc :id (uuid/next))
                                             (assoc :x final-x)
                                             (assoc :y final-y)))))

               (dnd/has-type? event "app/component")
               (let [{:keys [component file-id]} (dnd/get-data event "app/component")
                     shape (get-in component [:objects (:id component)])
                     final-x (- (:x viewport-coord) (/ (:width shape) 2))
                     final-y (- (:y viewport-coord) (/ (:height shape) 2))]
                 (st/emit! (dwl/instantiate-component file-id
                                                      (:id component)
                                                      (gpt/point final-x final-y))))

               ;; Will trigger when the user drags an image from a browser to the viewport
               (dnd/has-type? event "text/uri-list")
               (let [data  (dnd/get-data event "text/uri-list")
                     lines (str/lines data)
                     urls  (filter #(and (not (str/blank? %))
                                         (not (str/starts-with? % "#")))
                                   lines)
                     params {:file-id (:id file)
                             :uris urls}]
                 (st/emit! (dw/upload-media-workspace params viewport-coord)))

               ;; Will trigger when the user drags an SVG asset from the assets panel
               (and (dnd/has-type? event "text/asset-id") (= asset-type "image/svg+xml"))
               (let [path (cfg/resolve-file-media {:id asset-id})
                     params {:file-id (:id file)
                             :uris [path]
                             :name asset-name
                             :mtype asset-type}]
                 (st/emit! (dw/upload-media-workspace params viewport-coord)))

               ;; Will trigger when the user drags an image from the assets SVG
               (dnd/has-type? event "text/asset-id")
               (let [params {:file-id (:id file)
                             :object-id asset-id
                             :name asset-name}]
                 (st/emit! (dw/clone-media-object
                            (with-meta params
                              {:on-success #(on-image-uploaded % viewport-coord)}))))

               ;; Will trigger when the user drags a file from their file explorer into the viewport
               ;; Or the user pastes an image
               ;; Or the user uploads an image using the image tool
               :else
               (let [files  (dnd/get-files event)
                     params {:file-id (:id file)
                             :data (seq files)}]
                 (st/emit! (dw/upload-media-workspace params viewport-coord)))))))

        on-paste
        (mf/use-callback
         (fn [event]
           ;; We disable the paste just after mouse-up of a middle button so when panning won't
           ;; paste the content into the workspace
           (let [tag-name (-> event dom/get-target dom/get-tag-name)]
             (when (and (not (#{"INPUT" "TEXTAREA"} tag-name)) (not @disable-paste))
               (st/emit! (dw/paste-from-event event @in-viewport?))))))

        on-resize
        (mf/use-callback
         (fn [event]
           (let [node (mf/ref-val viewport-ref)
                 prnt (dom/get-parent node)
                 size (dom/get-client-size prnt)]
             ;; We schedule the event so it fires after `initialize-page` event
             (timers/schedule #(st/emit! (dw/update-viewport-size size))))))

        options (mf/deref refs/workspace-page-options)]

    (mf/use-layout-effect
     (fn []
       (let [node (mf/ref-val viewport-ref)
             prnt (dom/get-parent node)

             keys [(events/listen js/document EventType.KEYDOWN on-key-down)
                   (events/listen js/document EventType.KEYUP on-key-up)
                   (events/listen node EventType.MOUSEMOVE on-mouse-move)
                   ;; bind with passive=false to allow the event to be cancelled
                   ;; https://stackoverflow.com/a/57582286/3219895
                   (events/listen js/window EventType.WHEEL on-mouse-wheel #js {:passive false})
                   (events/listen js/window EventType.RESIZE on-resize)
                   (events/listen js/window EventType.PASTE on-paste)]]

         (fn []
           (doseq [key keys]
             (events/unlistenByKey key))))))

    (mf/use-layout-effect
     (fn []
       (mf/deps page-id)
       (let [node (mf/ref-val viewport-ref)
             prnt (dom/get-parent node)
             size (dom/get-client-size prnt)]
         ;; We schedule the event so it fires after `initialize-page` event
         (timers/schedule #(st/emit! (dw/initialize-viewport size))))))

    (mf/use-effect
     (mf/deps @cursor @alt? panning drawing-tool drawing-path?)
     (fn []
       (let [new-cursor
             (cond
               panning                     (get-cursor :hand)
               (= drawing-tool :comments)  (get-cursor :comments)
               (= drawing-tool :frame)     (get-cursor :create-artboard)
               (= drawing-tool :rect)      (get-cursor :create-rectangle)
               (= drawing-tool :circle)    (get-cursor :create-ellipse)
               (or (= drawing-tool :path)
                   drawing-path?)          (get-cursor :pen)
               (= drawing-tool :curve)     (get-cursor :pencil)
               drawing-tool                (get-cursor :create-shape)
               @alt?                       (get-cursor :duplicate)
               :else                       (get-cursor :pointer-inner))]

         (when (not= @cursor new-cursor)
           (reset! cursor new-cursor)))))

    (mf/use-layout-effect (mf/deps layout) on-resize)
    (hooks/use-stream ms/keyboard-alt #(reset! alt? %))

    [:*
     (when picking-color?
       [:& pixel-overlay {:vport vport
                          :vbox vbox
                          :viewport @viewport-node
                          :options options
                          :layout layout}])

     (when (= drawing-tool :comments)
       [:& comments-layer {:vbox vbox
                           :vport vport
                           :zoom zoom
                           :drawing drawing
                           :page-id page-id
                           :file-id (:id file)}])

     (when-not css-mouse?
       [:& render-cursor {:viewport @viewport-node
                          :cursor @cursor}])

     [:svg.viewport
      {:xmlns      "http://www.w3.org/2000/svg"
       :xmlnsXlink "http://www.w3.org/1999/xlink"
       :preserveAspectRatio "xMidYMid meet"
       :key page-id
       :width (:width vport 0)
       :height (:height vport 0)
       :view-box (format-viewbox vbox)
       :ref #(do (mf/set-ref-val! viewport-ref %)
                 (reset! viewport-node %))
       :class (when drawing-tool "drawing")
       :style {:cursor (when css-mouse? @cursor)
               :background-color (get options :background "#E8E9EA")}
       :on-context-menu on-context-menu
       :on-click on-click
       :on-double-click on-double-click
       :on-mouse-down on-mouse-down
       :on-mouse-up on-mouse-up
       :on-pointer-down on-pointer-down
       :on-pointer-up on-pointer-up
       :on-pointer-enter #(reset! in-viewport? true)
       :on-pointer-leave #(reset! in-viewport? false)
       :on-drag-enter on-drag-enter
       :on-drag-over on-drag-over
       :on-drop on-drop}

      [:g {:style {:pointer-events (if (contains? layout :comments)
                                     "none"
                                     "auto")}}
       [:& frames {:key page-id
                   :hover hover
                   :selected selected
                   :edition edition}]

       [:g {:style {:display (when (not= :move transform) "none")}}
        [:& ghost-frames {:modifiers modifiers
                          :selected selected}]]

       (when (seq selected)
         [:& selection-handlers {:selected selected
                                 :zoom zoom
                                 :edition edition
                                 :show-distances (and (not transform) @alt?)}])

       (when (= (count selected) 1)
         [:& gradient-handlers {:id (first selected)
                                :zoom zoom}])

       (when drawing-obj
         [:& draw-area {:shape drawing-obj
                        :zoom zoom
                        :tool drawing-tool
                        :modifiers modifiers}])

       (when show-grids?
         [:& frame-grid {:zoom zoom}])

       (when show-snap-points?
         [:& snap-points {:layout layout
                          :transform transform
                          :drawing drawing-obj
                          :zoom zoom
                          :page-id page-id
                          :selected selected
                          :modifiers modifiers}])

       (when show-snap-distance?
         [:& snap-distances {:layout layout
                             :zoom zoom
                             :transform transform
                             :selected selected
                             :page-id page-id}])

       (when tooltip
         [:& cursor-tooltip {:zoom zoom :tooltip tooltip}])]

      [:& presence/active-cursors {:page-id page-id}]
      [:& selection-rect {:data selrect}]

      (when (= options-mode :prototype)
        [:& interactions {:selected selected}])]]))


(mf/defc viewport-actions
  {::mf/wrap [mf/memo]}
  []
  (let [edition (mf/deref refs/selected-edition)
        selected (mf/deref refs/selected-objects)
        shape (-> selected first)]
    (when (and (= (count selected) 1)
               (= (:id shape) edition)
               (= :path (:type shape)))
      [:div.viewport-actions
       [:& path-actions {:shape shape}]])))

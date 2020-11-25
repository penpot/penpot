;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.shapes.text
  (:require
   ["slate" :as slate]
   ["slate-react" :as rslate]
   [app.common.data :as d]
   [app.common.geom.shapes :as geom]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.texts :as dwt]
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as muc]
   [app.main.ui.cursors :as cur]
   [app.main.ui.keyboard :as kbd]
   [app.main.ui.shapes.filters :as filters]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.shapes.text :as text]
   [app.main.ui.workspace.shapes.common :as common]
   [app.util.color :as color]
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.util.text :as ut]
   [app.util.timers :as timers]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [goog.events :as events]
   [goog.object :as gobj]
   [rumext.alpha :as mf])
  (:import
   goog.events.EventType
   goog.events.KeyCodes))

;; --- Events

(defn handle-mouse-down
  [event {:keys [id group] :as shape}]
  (if (and (not (:blocked shape))
           (or @refs/selected-drawing-tool
               @refs/selected-edition))
    (dom/stop-propagation event)
    (common/on-mouse-down event shape)))

;; --- Text Wrapper for workspace

(declare text-shape-edit)
(declare text-shape)

(mf/defc text-wrapper
  {::mf/wrap-props false}
  [props]
  (let [shape             (unchecked-get props "shape")
        selected-iref     (mf/use-memo (mf/deps (:id shape))
                                       #(refs/make-selected-ref (:id shape)))
        selected?         (mf/deref selected-iref)
        edition           (mf/deref refs/selected-edition)
        current-transform (mf/deref refs/current-transform)
        render-editor     (mf/use-state false)

        edition?          (= edition (:id shape))

        embed-resources?  (mf/use-ctx muc/embed-ctx)

        on-mouse-down     #(handle-mouse-down % shape)
        on-context-menu   #(common/on-context-menu % shape)

        on-double-click
        (fn [event]
          (dom/stop-propagation event)
          (dom/prevent-default event)
          (when selected?
            (st/emit! (dw/start-edition-mode (:id shape)))))

        check?
        (and (#{:auto-width :auto-height} (:grow-type shape))
             selected?
             (not edition?)
             (not embed-resources?)
             (nil? current-transform))]

    (mf/use-effect
     (mf/deps check?)
     (fn []
       (let [sem (timers/schedule #(reset! render-editor check?))]
         #(rx/dispose! sem))))

    [:> shape-container {:shape shape
                         :on-double-click on-double-click
                         :on-mouse-down on-mouse-down
                         :on-context-menu on-context-menu}
     (when @render-editor
       [:g {:opacity 0
            :style {:pointer-events "none"}}
        ;; We only render the component for its side-effect
        [:& text-shape-edit {:shape shape
                             :read-only? true}]])

     (if edition?
       [:& text-shape-edit {:shape shape}]
       [:& text/text-shape {:shape shape
                            :selected? selected?}])]))

;; --- Text Editor Rendering

(defn- generate-root-styles
  [data props]
  (let [valign (obj/get data "vertical-align" "top")
        talign (obj/get data "text-align")
        shape  (obj/get props "shape")
        base   #js {:height "100%"
                    :width (:width shape)
                    :display "flex"}]
    (cond-> base
      (= valign "top") (obj/set! "alignItems" "flex-start")
      (= valign "center") (obj/set! "alignItems" "center")
      (= valign "bottom") (obj/set! "alignItems" "flex-end")
      (= talign "left") (obj/set! "justifyContent" "flex-start")
      (= talign "center") (obj/set! "justifyContent" "center")
      (= talign "right") (obj/set! "justifyContent" "flex-end")
      (= talign "justify") (obj/set! "justifyContent" "stretch"))))

(defn- generate-paragraph-styles
  [data]
  (let [base #js {:fontSize "14px"
                  :margin "inherit"
                  :lineHeight "1.2"}
        lh (obj/get data "line-height")
        ta (obj/get data "text-align")]
    (cond-> base
      ta (obj/set! "textAlign" ta)
      lh (obj/set! "lineHeight" lh))))

(defn- generate-text-styles
  [data]
  (let [letter-spacing (obj/get data "letter-spacing")
        text-decoration (obj/get data "text-decoration")
        text-transform (obj/get data "text-transform")
        line-height (obj/get data "line-height")

        font-id (obj/get data "font-id" (:font-id ut/default-text-attrs))
        font-variant-id (obj/get data "font-variant-id")

        font-family (obj/get data "font-family")
        font-size  (obj/get data "font-size")

        ;; Old properties for backwards compatibility
        fill (obj/get data "fill")
        opacity (obj/get data "opacity" 1)

        fill-color (obj/get data "fill-color" fill)
        fill-opacity (obj/get data "fill-opacity" opacity)
        fill-color-gradient (obj/get data "fill-color-gradient" nil)
        fill-color-gradient (when fill-color-gradient
                              (-> (js->clj fill-color-gradient :keywordize-keys true)
                                  (update :type keyword)))

        fill-color-ref-id (obj/get data "fill-color-ref-id")
        fill-color-ref-file (obj/get data "fill-color-ref-file")

        [r g b a] (uc/hex->rgba fill-color fill-opacity)
        background (if fill-color-gradient
                     (uc/gradient->css (js->clj fill-color-gradient))
                     (str/format "rgba(%s, %s, %s, %s)" r g b a))

        fontsdb (deref fonts/fontsdb)

        base #js {:textDecoration text-decoration
                  :textTransform text-transform
                  :lineHeight (or line-height "inherit")
                  "--text-color" background}]

    (when (and (string? letter-spacing)
               (pos? (alength letter-spacing)))
      (obj/set! base "letterSpacing" (str letter-spacing "px")))

    (when (and (string? font-size)
               (pos? (alength font-size)))
      (obj/set! base "fontSize" (str font-size "px")))

    (when (and (string? font-id)
               (pos? (alength font-id)))
      (let [font (get fontsdb font-id)]
        (let [font-family (or (:family font)
                              (obj/get data "fontFamily"))
              font-variant (d/seek #(= font-variant-id (:id %))
                                   (:variants font))
              font-style  (or (:style font-variant)
                              (obj/get data "fontStyle"))
              font-weight (or (:weight font-variant)
                              (obj/get data "fontWeight"))]
          (obj/set! base "fontFamily" font-family)
          (obj/set! base "fontStyle" font-style)
          (obj/set! base "fontWeight" font-weight))))

    base))

(mf/defc editor-root-node
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]
  (let [attrs  (obj/get props "attributes")
        childs (obj/get props "children")
        data   (obj/get props "element")
        type   (obj/get data "type")
        style  (generate-root-styles data props)
        attrs  (obj/set! attrs "style" style)
        attrs  (obj/set! attrs "className" type)]
    [:> :div attrs childs]))

(mf/defc editor-paragraph-set-node
  {::mf/wrap-props false}
  [props]
  (let [attrs  (obj/get props "attributes")
        childs (obj/get props "children")
        data   (obj/get props "element")
        type   (obj/get data "type")
        shape (obj/get props "shape")

        ;; The position absolute is used so the paragraph is "outside"
        ;; the normal layout and can grow outside its parent
        ;; We use this element to measure the size of the text
        style  #js {:display "inline-block"
                    :position "absolute"}
        attrs  (obj/set! attrs "style" style)
        attrs  (obj/set! attrs "className" type)]
    [:> :div attrs childs]))

(mf/defc editor-paragraph-node
  {::mf/wrap-props false}
  [props]
  (let [attrs  (obj/get props "attributes")
        childs (obj/get props "children")
        data   (obj/get props "element")
        style  (generate-paragraph-styles data)
        attrs  (obj/set! attrs "style" style)]
    [:> :p attrs childs]))

(mf/defc editor-text-node
  {::mf/wrap-props false}
  [props]
  (let [attrs (obj/get props "attributes")
        childs (obj/get props "children")
        data   (obj/get props "leaf")
        style  (generate-text-styles data)
        attrs  (-> attrs
                   (obj/set! "style" style)
                   (obj/set! "className" "text-node"))]
    [:> :span attrs childs]))

(defn- render-element
  [shape props]
  (mf/html
   (let [element (obj/get props "element")
         props (obj/merge! props #js {:shape shape})]
     (case (obj/get element "type")
       "root"          [:> editor-root-node props]
       "paragraph-set" [:> editor-paragraph-set-node props]
       "paragraph"     [:> editor-paragraph-node props]
       nil))))

(defn- render-text
  [props]
  (mf/html
   [:> editor-text-node props]))

;; --- Text Shape Edit

(defn- initial-text
  [text]
  (clj->js
   [{:type "root"
     :children [{:type "paragraph-set"
                 :children [{:type "paragraph"
                             :children [{:text (or text "")}]}]}]}]))
(defn- parse-content
  [content]
  (cond
    (string? content) (initial-text content)
    (map? content) (clj->js [content])
    :else (initial-text "")))

(defn- content-size
  [node]
  (let [current (count (:text node))
        children-count (->> node :children (map content-size) (reduce +))]
    (+ current children-count)))

(defn fix-gradients
  "Fix for the gradient types that need to be keywords"
  [content]
  (let [fix-node
        (fn [node]
          (d/update-in-when node [:fill-color-gradient :type] keyword))]
    (ut/map-node fix-node content)))

(mf/defc text-shape-edit
  {::mf/wrap [mf/memo]}
  [{:keys [shape read-only?] :or {read-only? false} :as props}]
  (let [{:keys [id x y width height content grow-type]} shape
        zoom     (mf/deref refs/selected-zoom)
        state    (mf/use-state #(parse-content content))
        editor   (mf/use-memo #(dwt/create-editor))
        self-ref      (mf/use-ref)
        selecting-ref (mf/use-ref)
        measure-ref (mf/use-ref)

        content-var (mf/use-var content)

        on-close
        (fn []
          (when (not read-only?)
            (st/emit! dw/clear-edition-mode))
          (when (= 0 (content-size @content-var))
            (st/emit! (dw/delete-shapes [id]))))

        on-click-outside
        (fn [event]
          (dom/prevent-default event)
          (dom/stop-propagation event)


          (let [sidebar (dom/get-element "settings-bar")
                assets (dom/get-element-by-class "assets-bar")
                cpicker (dom/get-element-by-class "colorpicker-tooltip")
                self    (mf/ref-val self-ref)
                target  (dom/get-target event)
                selecting? (mf/ref-val selecting-ref)]
            (when-not (or (and sidebar (.contains sidebar target))
                          (and assets  (.contains assets target))
                          (and self    (.contains self target))
                          (and cpicker (.contains cpicker target)))
              (if selecting?
                (mf/set-ref-val! selecting-ref false)
                (on-close)))))

        on-mouse-down
        (fn [event]
          (mf/set-ref-val! selecting-ref true))

        on-mouse-up
        (fn [event]
          (mf/set-ref-val! selecting-ref false))

        on-key-up
        (fn [event]
          (dom/stop-propagation event)
          (when (= (.-keyCode event) 27) ; ESC
            (do
              (st/emit! :interrupt)
              (on-close))))

        on-mount
        (fn []
          (when (not read-only?)
            (let [lkey1 (events/listen (dom/get-root) EventType.CLICK on-click-outside)
                  lkey2 (events/listen (dom/get-root) EventType.KEYUP on-key-up)]
              (st/emit! (dwt/assign-editor id editor)
                        dwc/start-undo-transaction)

              #(do
                 (st/emit! (dwt/assign-editor id nil)
                           dwc/commit-undo-transaction)
                 (events/unlistenByKey lkey1)
                 (events/unlistenByKey lkey2)))))

        on-focus
        (fn [event]
          (when (not read-only?)
            (dwt/editor-select-all! editor)))

        on-change
        (mf/use-callback
         (fn [val]
           (when (not read-only?)
             (let [content (js->clj val :keywordize-keys true)
                   content (first content)
                   content (fix-gradients content)]
               ;; Append timestamp so we can react to cursor change events
               (st/emit! (dw/update-shape id {:content (assoc content :ts (js->clj (.now js/Date)))}))
               (reset! state val)
               (reset! content-var content)))))]

    (mf/use-effect on-mount)

    (mf/use-effect
     (mf/deps content)
     (fn []
       (reset! state (parse-content content))
       (reset! content-var content)))

    ;; Checks the size of the wrapper to update if it were necesary
    (mf/use-effect
     (mf/deps shape)

     (fn []
       (fonts/ready
        #(let [self-node (mf/ref-val self-ref)
               paragraph-node (when self-node (dom/query self-node ".paragraph-set"))]
           (when paragraph-node
             (let [
                   {bb-w :width bb-h :height} (dom/get-bounding-rect paragraph-node)
                   width (max (/ bb-w zoom) 7)
                   height (max (/ bb-h zoom) 16)
                   undo-transaction (get-in @st/state [:workspace-undo :transaction])]
               (when (not undo-transaction) (st/emit! dwc/start-undo-transaction))
               (when (or (not= (:width shape) width)
                         (not= (:height shape) height))
                 (cond
                   (and (:overflow-text shape) (not= :fixed (:grow-type shape)))
                   (st/emit! (dwt/update-overflow-text id false))

                   (and (= :fixed (:grow-type shape)) (not (:overflow-text shape)) (> height (:height shape)))
                   (st/emit! (dwt/update-overflow-text id true))

                   (and (= :fixed (:grow-type shape)) (:overflow-text shape) (<= height (:height shape)))
                   (st/emit! (dwt/update-overflow-text id false))

                   (= grow-type :auto-width)
                   (st/emit! (dw/update-dimensions [id] :width width)
                             (dw/update-dimensions [id] :height height))

                   (= grow-type :auto-height)
                   (st/emit! (dw/update-dimensions [id] :height height))
                   ))
               (when (not undo-transaction) (st/emit! dwc/discard-undo-transaction))))))))

    [:foreignObject {:ref self-ref
                     :transform (geom/transform-matrix shape)
                     :x x :y y
                     :width (if (= :auto-width grow-type) 10000 width)
                     :height height}
     [:style "span { line-height: inherit; }
              .text-node { background: var(--text-color); -webkit-text-fill-color: transparent; -webkit-background-clip: text;"]
     [:> rslate/Slate {:editor editor
                       :value @state
                       :on-change on-change}
      [:> rslate/Editable
       {:auto-focus (when (not read-only?) "true")
        :spell-check "false"
        :on-focus on-focus
        :class "rich-text"
        :style {:cursor cur/text
                :width (:width shape)}
        :render-element #(render-element shape %)
        :render-leaf render-text
        :on-mouse-up on-mouse-up
        :on-mouse-down on-mouse-down
        :on-blur (fn [event]
                   (dom/prevent-default event)
                   (dom/stop-propagation event)
                   ;; WARN: monky patch
                   (obj/set! slate/Transforms "deselect" (constantly nil)))
        :placeholder (when (= :fixed grow-type) "Type some text here...")}]]]))

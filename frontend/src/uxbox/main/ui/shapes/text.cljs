;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.text
  (:require
   [cuerdas.core :as str]
   [goog.events :as events]
   [goog.object :as gobj]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.common.data :as d]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.data.workspace.texts :as dwt]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.shapes.common :as common]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.fonts :as fonts]
   [uxbox.util.color :as color]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.shapes :as geom]
   [uxbox.util.object :as obj]
   [uxbox.util.geom.matrix :as gmt]
   ["slate" :as slate]
   ["slate-react" :as rslate])
  (:import goog.events.EventType
           goog.events.KeyCodes))

;; --- Events

(defn handle-mouse-down
  [event {:keys [id group] :as shape}]
  (if (and (not (:blocked shape))
           (or @refs/selected-drawing-tool
               @refs/selected-edition))
    (dom/stop-propagation event)
    (common/on-mouse-down event shape)))

;; --- Text Wrapper

(declare text-shape-html)
(declare text-shape-edit)
(declare text-shape)

(mf/defc text-wrapper
  [{:keys [shape] :as props}]
  (let [{:keys [id x1 y1 content group]} shape
        selected (mf/deref refs/selected-shapes)
        edition (mf/deref refs/selected-edition)
        edition? (= edition id)
        selected? (and (contains? selected id)
                       (= (count selected) 1))

        on-mouse-down #(handle-mouse-down % shape)
        on-context-menu #(common/on-context-menu % shape)

        on-double-click
        (fn [event]
          (dom/stop-propagation event)
          (dom/prevent-default event)
          (when selected?
            (st/emit! (dw/start-edition-mode (:id shape)))))]

    [:g.shape {:on-double-click on-double-click
               :on-mouse-down on-mouse-down
               :on-context-menu on-context-menu}
     (if edition?
       [:& text-shape-edit {:shape shape}]
       [:& text-shape {:shape shape
                       :selected? selected?}])]))

;; --- Text Editor Rendering

(defn- generate-root-styles
  [data]
  (let [valign (obj/get data "vertical-align")
        base   #js {:height "100%"
                    :width "100%"
                    :display "flex"}]
    (cond-> base
      (= valign "top") (obj/set! "alignItems" "flex-start")
      (= valign "center") (obj/set! "alignItems" "center")
      (= valign "bottom") (obj/set! "alignItems" "flex-end"))))

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

        font-id (obj/get data "font-id")
        font-variant-id (obj/get data "font-variant-id")

        font-family (obj/get data "font-family")
        font-size  (obj/get data "font-size")
        fill (obj/get data "fill")
        opacity (obj/get data "opacity")
        fontsdb (deref fonts/fontsdb)

        base #js {:textDecoration text-decoration
                  :color fill
                  :opacity opacity
                  :textTransform text-transform}]

    (when (and (string? letter-spacing)
               (pos? (alength letter-spacing)))
      (obj/set! base "letterSpacing" (str letter-spacing "px")))

    (when (and (string? font-size)
               (pos? (alength font-size)))
      (obj/set! base "fontSize" (str font-size "px")))

    (when (and (string? font-id)
               (pos? (alength font-id)))
      (let [font (get fontsdb font-id)]
        (fonts/ensure-loaded! font-id)
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
        style  (generate-root-styles data)
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
        style  #js {:display "inline-block"
                    :width "100%"}
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
        attrs  (obj/set! attrs "style" style)]
    [:> :span attrs childs]))

(defn- render-element
  [props]
  (mf/html
   (let [element (obj/get props "element")]
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

(mf/defc text-shape-edit
  {::mf/wrap [mf/memo]}
  [{:keys [shape] :as props}]
  (let [{:keys [id x y width height content]} shape

        state    (mf/use-state #(parse-content content))
        editor   (mf/use-memo #(dwt/create-editor))
        self-ref      (mf/use-ref)
        selecting-ref (mf/use-ref)

        on-close
        (fn []
          (st/emit! dw/clear-edition-mode))

        on-click
        (fn [event]
          (dom/prevent-default event)
          (dom/stop-propagation event)
          (let [sidebar (dom/get-element "settings-bar")
                cpicker (dom/get-element-by-class "colorpicker-tooltip")
                self    (mf/ref-val self-ref)
                target  (dom/get-target event)
                selecting? (mf/ref-val selecting-ref)]
            (when-not (or (.contains sidebar target)
                          (.contains self target)
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

        on-keyup
        (fn [event]
          (when (= (.-keyCode event) 27) ; ESC
            (on-close)))

        on-mount
        (fn []
          (let [lkey1 (events/listen js/document EventType.CLICK on-click)
                lkey2 (events/listen js/document EventType.KEYUP on-keyup)]
            (st/emit! (dwt/assign-editor id editor))
            #(do
               (st/emit! (dwt/assign-editor id nil))
               (events/unlistenByKey lkey1)
               (events/unlistenByKey lkey2))))

        on-change
        (mf/use-callback
         (fn [val]
           (let [content (js->clj val :keywordize-keys true)
                 content (first content)]
             (st/emit! (dw/update-shape id {:content content}))
             (reset! state val))))]

    (mf/use-effect on-mount)

    [:foreignObject {:transform (geom/transform-matrix shape)
                     :x x :y y :width width :height height :ref self-ref}
     [:> rslate/Slate {:editor editor
                       :value @state
                       :on-change on-change}
      [:> rslate/Editable
       {:auto-focus "true"
        :spell-check "false"
        :class "rich-text"
        :render-element render-element
        :render-leaf render-text
        :on-mouse-up on-mouse-up
        :on-mouse-down on-mouse-down
        :on-blur (fn [event]
                   (dom/prevent-default event)
                   (dom/stop-propagation event)
                   ;; WARN: monky patch
                   (obj/set! slate/Transforms "deselect" (constantly nil)))
        :placeholder "Type some text here..."}]]]))

;; --- Text Shape Wrapper

(defn- render-text-node
  ([node] (render-text-node 0 node))
  ([index {:keys [type text children] :as node}]
   (mf/html
    (if (string? text)
      (let [style (generate-text-styles (clj->js node))]
        [:span {:style style :key index} text])
      (let [children (map-indexed render-text-node children)]
        (case type
          "root"
          (let [style (generate-root-styles (clj->js node))]
            [:div.root.rich-text {:key index :style style} children])

          "paragraph-set"
          (let [style #js {:display "inline-block"
                           :width "100%"}]
            [:div.paragraphs {:key index :style style} children])

          "paragraph"
          (let [style (generate-paragraph-styles (clj->js node))]
            [:p {:key index :style style} children])

          nil))))))

(mf/defc text-content
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]
  (let [root (obj/get props "content")]
    (render-text-node root)))

(mf/defc text-shape
  [{:keys [shape selected?] :as props}]
  (let [{:keys [id x y width height rotation content]} shape]
    [:foreignObject {:x x
                     :y y
                     :transform (geom/transform-matrix shape)
                     :id (str id)
                     :width width
                     :height height}
     [:& text-content {:content (:content shape)}]]))


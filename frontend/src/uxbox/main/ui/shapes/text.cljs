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
  [{:keys [shape frame] :as props}]
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
       [:& text-shape-edit {:shape (geom/transform-shape frame shape)}]
       [:& text-shape {:shape (geom/transform-shape frame shape)
                       :selected? selected?}])]))

;; --- Text Rendering

(defn obj-assoc!
  [obj attr value]
  (unchecked-set obj attr value)
  obj)

(defn- generate-text-box-styles
  [data]
  (let [valign (unchecked-get data "verticalAlign")
        base   #js {:height "100%"
                    :width "100%"
                    :display "flex"}]
    (cond-> base
      (= valign "top") (obj-assoc! "alignItems" "flex-start")
      (= valign "center") (obj-assoc! "alignItems" "center")
      (= valign "bottom") (obj-assoc! "alignItems" "flex-end"))))

(mf/defc rt-text-box
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]
  (let [attrs  (unchecked-get props "attributes")
        childs (unchecked-get props "children")
        data   (unchecked-get props "element")
        type   (unchecked-get data "type")
        style  (generate-text-box-styles data)
        attrs  (obj-assoc! attrs "style" style)
        attrs  (obj-assoc! attrs "className" type)]
    [:> :div attrs childs]))

(defn- generate-text-styles
  [data]
  (let [valign (unchecked-get data "verticalAlign")
        base #js {:display "inline-block"
                  :width "100%"}]
    base))

(mf/defc rt-text
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]
  (let [attrs  (unchecked-get props "attributes")
        childs (unchecked-get props "children")
        data   (unchecked-get props "element")
        type   (unchecked-get data "type")
        style  (generate-text-styles data)
        attrs  (obj-assoc! attrs "style" style)
        attrs  (obj-assoc! attrs "className" type)]
    [:> :div attrs childs]))

(defn- generate-paragraph-styles
  [data]
  (let [base #js {:fontSize "14px"
                  :margin "inherit"
                  :lineHeight "1.2"}
        lh (unchecked-get data "lineHeight")
        ta (unchecked-get data "textAlign")]
    (cond-> base
      ta (obj-assoc! "textAlign" ta)
      lh (obj-assoc! "lineHeight" lh))))

(mf/defc rt-pharagraph
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]
  (let [attrs  (unchecked-get props "attributes")
        childs (unchecked-get props "children")
        data   (unchecked-get props "element")
        style  (generate-paragraph-styles data)
        attrs  (obj-assoc! attrs "style" style)]
    [:> :p attrs childs]))

(defn- generate-leaf-styles
  [data]
  (let [letter-spacing (unchecked-get data "letterSpacing")
        text-decoration (unchecked-get data "textDecoration")
        text-transform (unchecked-get data "textTransform")

        font-id (unchecked-get data "fontId")
        font-variant-id (unchecked-get data "fontVariantId")

        font-family (unchecked-get data "fontFamily")
        font-size  (unchecked-get data "fontSize")

        fontsdb (mf/deref fonts/fontsdb)

        base #js {:textDecoration text-decoration
                  :textTransform text-transform}]

    (when (and (string? letter-spacing)
               (pos? (alength letter-spacing)))
      (obj-assoc! base "letterSpacing" (str letter-spacing "px")))

    (when (and (string? font-size)
               (pos? (alength font-size)))
      (obj-assoc! base "fontSize" (str font-size "px")))

    (when (and (string? font-id)
               (pos? (alength font-id)))
      (let [font (get fontsdb font-id)]
        (fonts/ensure-loaded! font-id)
        (let [font-family (or (:family font)
                              (unchecked-get data "fontFamily"))
              font-variant (d/seek #(= font-variant-id (:name %))
                                   (:variants font))
              font-style  (or (:style font-variant)
                              (unchecked-get data "fontStyle"))
              font-weight (or (:weight font-variant)
                              (unchecked-get data "fontWeight"))]
          (obj-assoc! base "fontFamily" font-family)
          (obj-assoc! base "fontStyle" font-style)
          (obj-assoc! base "fontWeight" font-weight))))

    base))

(mf/defc rt-leaf
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]
  (let [attrs (unchecked-get props "attributes")
        childs (unchecked-get props "children")
        data   (unchecked-get props "leaf")
        style  (generate-leaf-styles data)
        attrs  (obj-assoc! attrs "style" style)]
    [:> :span attrs childs]))

(defn- render-element
  [props]
  (mf/html
   (let [element (unchecked-get props "element")]
     (case (unchecked-get element "type")
       "text-box"  [:> rt-text-box props]
       "text"      [:> rt-text props]
       "paragraph" [:> rt-pharagraph props]
       nil))))

(defn- render-leaf
  [props]
  (mf/html
   [:> rt-leaf props]))

;; --- Text Shape Edit

(defn- initial-text
  ([] (initial-text ""))
  ([text]
   #js [#js {:type "text-box"
             :children #js [#js {:type "text"
                                 :children #js [#js {:type "paragraph"
                                                     :children #js [#js {:text text}]}]}]}]))
(defn- parse-content
  [content]
  (cond
    (string? content) (initial-text content)
    (vector? content) (clj->js content)
    (object? content) content
    :else (initial-text)))

(mf/defc text-shape-edit
  {::mf/wrap [mf/memo]}
  [{:keys [shape] :as props}]
  (let [{:keys [id x y width height content]} shape

        state  (mf/use-state #(parse-content content))
        value  (mf/use-var @state)

        editor (mf/use-memo #(rslate/withReact (slate/createEditor)))
        self-ref (mf/use-ref)

        on-close
        (fn []
          (st/emit! dw/clear-edition-mode
                    dw/deselect-all))

        on-click
        (fn [event]
          (dom/prevent-default event)
          (dom/stop-propagation event)
          (let [sidebar (dom/get-element "settings-bar")
                self    (mf/ref-val self-ref)
                target  (dom/get-target event)]
            (when-not (or (.contains sidebar target)
                          (.contains self target))
              (on-close))))

        on-keyup
        (fn [event]
          (when (= (.-keyCode event) 27) ; ESC
            (on-close)))

        on-mount
        (fn []
          (let [
                lkey1 (events/listen js/document EventType.CLICK on-click)
                lkey2 (events/listen js/document EventType.KEYUP on-keyup)]
            (st/emit! (dwt/assign-editor editor))
            #(let [content (js->clj @value)]
               (st/emit! (dwt/assign-editor nil)
                         (dw/update-shape id {:content content}))
               (events/unlistenByKey lkey1)
               (events/unlistenByKey lkey2))))

        on-change
        (mf/use-callback
         (fn [val]
           (st/emit! (dwt/assign-editor editor))
           (reset! state val)
           (reset! value val)))]

    (mf/use-effect on-mount)

    [:foreignObject {:x x :y y :width width :height height :ref self-ref}
     [:> rslate/Slate {:editor editor
                       :value @state
                       :on-change on-change}
      [:> rslate/Editable
       {:auto-focus "true"
        :spell-check "false"
        :class "rich-text"
        :render-element render-element
        :render-leaf render-leaf
        :on-blur (fn [event]
                   (dom/prevent-default event)
                   (dom/stop-propagation event)
                   ;; WARN: monky patch
                   (unchecked-set slate/Transforms "deselect" (constantly nil)))
        :placeholder "Type some text here..."}]]]))

;; --- Text Shape Wrapper

(mf/defc text-shape
  [{:keys [shape selected?] :as props}]
  (let [{:keys [id x y width height rotation content]} shape
        transform (when (and rotation (pos? rotation))
                    (str/format "rotate(%s %s %s)"
                                rotation
                                (+ x (/ width 2))
                                (+ y (/ height 2))))

        content (parse-content content)
        editor (mf/use-memo #(rslate/withReact (slate/createEditor)))

        on-mount
        (fn []
          (when selected?
            (st/emit! (dwt/assign-editor editor))
            #(st/emit! (dwt/assign-editor nil))))

        render-element (mf/use-callback render-element)
        render-leaf (mf/use-callback render-leaf)]

    (mf/use-effect (mf/deps id selected?) on-mount)

    [:foreignObject {:x x
                     :y y
                     :transform transform
                     :id (str id)
                     :width width
                     :height height}

     [:> rslate/Slate {:editor editor
                       :value content}
      [:> rslate/Editable {:auto-focus "false"
                           :read-only "true"
                           :class "rich-text"
                           :render-element render-element
                           :render-leaf render-leaf
                           :placeholder "Type some text here..."}]]]))

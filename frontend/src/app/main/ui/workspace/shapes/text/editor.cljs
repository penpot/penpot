;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.shapes.text.editor
  (:require
   ["slate" :as slate]
   ["slate-react" :as rslate]
   [goog.events :as events]
   [rumext.alpha :as mf]
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.util.dom :as dom]
   [app.util.text :as ut]
   [app.util.object :as obj]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.texts :as dwt]
   [app.main.ui.cursors :as cur]
   [app.main.ui.shapes.text.styles :as sts])
  (:import
   goog.events.EventType
   goog.events.KeyCodes))

;; --- Data functions

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

(defn- fix-gradients
  "Fix for the gradient types that need to be keywords"
  [content]
  (let [fix-node
        (fn [node]
          (d/update-in-when node [:fill-color-gradient :type] keyword))]
    (ut/map-node fix-node content)))

;; --- Text Editor Rendering

(mf/defc editor-root-node
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]
  (let [
        childs (obj/get props "children")
        data   (obj/get props "element")
        type   (obj/get data "type")
        style  (sts/generate-root-styles data props)
        attrs  (-> (obj/get props "attributes")
                   (obj/set! "style" style)
                   (obj/set! "className" type))]
    [:> :div attrs childs]))

(mf/defc editor-paragraph-set-node
  {::mf/wrap-props false}
  [props]
  (let [childs (obj/get props "children")
        data   (obj/get props "element")
        type   (obj/get data "type")
        shape (obj/get props "shape")
        style  (sts/generate-paragraph-set-styles data props)
        attrs  (-> (obj/get props "attributes")
                   (obj/set! "style" style)
                   (obj/set! "className" type))]
    [:> :div attrs childs]))

(mf/defc editor-paragraph-node
  {::mf/wrap-props false}
  [props]
  (let [
        childs (obj/get props "children")
        data   (obj/get props "element")
        type   (obj/get data "type")
        style  (sts/generate-paragraph-styles data props)
        attrs  (-> (obj/get props "attributes")
                   (obj/set! "style" style)
                   (obj/set! "className" type))]
    [:> :p attrs childs]))

(mf/defc editor-text-node
  {::mf/wrap-props false}
  [props]
  (let [childs (obj/get props "children")
        data   (obj/get props "leaf")
        type   (obj/get data "type")
        style  (sts/generate-text-styles data props)
        attrs  (-> (obj/get props "attributes")
                   (obj/set! "style" style))
        gradient (obj/get data "fill-color-gradient" nil)]
    (if gradient
      (obj/set! attrs "className" (str type " gradient"))
      (obj/set! attrs "className" type))
    [:> :span attrs childs]))

(defn- render-element
  [shape props]
  (mf/html
   (let [element (obj/get props "element")
         type    (obj/get element "type")
         props   (obj/merge! props #js {:shape shape})
         props   (cond-> props
                   (= type "root") (obj/set! "key" "root")
                   (= type "paragraph-set") (obj/set! "key" "paragraph-set"))]

     (case type
       "root"          [:> editor-root-node props]
       "paragraph-set" [:> editor-paragraph-set-node props]
       "paragraph"     [:> editor-paragraph-node props]
       nil))))

(defn- render-text
  [props]
  (mf/html
   [:> editor-text-node props]))

;; --- Text Shape Edit

(mf/defc text-shape-edit-html
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false
   ::mf/forward-ref true}
  [props ref]
  (let [shape (unchecked-get props "shape")
        node-ref (unchecked-get props "node-ref")

        {:keys [id x y width height content grow-type]} shape
        zoom     (mf/deref refs/selected-zoom)
        state    (mf/use-state #(parse-content content))
        editor   (mf/use-memo #(dwt/create-editor))
        self-ref      (mf/use-ref)
        selecting-ref (mf/use-ref)
        measure-ref (mf/use-ref)

        content-var (mf/use-var content)

        on-close
        (fn []
          (st/emit! dw/clear-edition-mode)
          (when (= 0 (content-size @content-var))
            (st/emit! (dw/delete-shapes [id]))))

        on-click-outside
        (fn [event]
          (let [options (dom/get-element-by-class "element-options")
                assets (dom/get-element-by-class "assets-bar")
                cpicker (dom/get-element-by-class "colorpicker-tooltip")
                self    (mf/ref-val self-ref)
                target  (dom/get-target event)
                selecting? (mf/ref-val selecting-ref)]
            (when-not (or (and options (.contains options target))
                          (and assets  (.contains assets target))
                          (and self    (.contains self target))
                          (and cpicker (.contains cpicker target)))
              (do
                (dom/prevent-default event)
                (dom/stop-propagation event)

                (if selecting?
                  (mf/set-ref-val! selecting-ref false)
                  (on-close))))))

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
          (let [lkey1 (events/listen (dom/get-root) EventType.CLICK on-click-outside)
                lkey2 (events/listen (dom/get-root) EventType.KEYUP on-key-up)]
            (st/emit! (dwt/assign-editor id editor)
                      (dwc/start-undo-transaction))

            #(do
               (st/emit! (dwt/assign-editor id nil)
                         (dwc/commit-undo-transaction))
               (events/unlistenByKey lkey1)
               (events/unlistenByKey lkey2))))

        on-focus
        (fn [event]
          (dwt/editor-select-all! editor))

        on-change
        (mf/use-callback
         (fn [val]
           (let [content (js->clj val :keywordize-keys true)
                 content (first content)
                 content (fix-gradients content)]
             ;; Append timestamp so we can react to cursor change events
             (st/emit! (dw/update-shape id {:content (assoc content :ts (js->clj (.now js/Date)))}))
             (reset! state val)
             (reset! content-var content))))]

    (mf/use-effect on-mount)

    (mf/use-effect
     (mf/deps content)
     (fn []
       (reset! state (parse-content content))
       (reset! content-var content)))

    [:div.text-editor {:ref self-ref}
     [:style "span { line-height: inherit; }
              .gradient { background: var(--text-color); -webkit-text-fill-color: transparent; -webkit-background-clip: text;"]
     [:> rslate/Slate {:editor editor
                       :value @state
                       :on-change on-change}
      [:> rslate/Editable
       {:auto-focus "true"
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

(mf/defc text-shape-edit
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false
   ::mf/forward-ref true}
  [props ref]
  (let [shape (unchecked-get props "shape")
        {:keys [x y width height grow-type]} shape]
    [:foreignObject {:transform (gsh/transform-matrix shape)
                     :x x :y y
                     :width  (if (#{:auto-width} grow-type) 10000 width)
                     :height (if (#{:auto-height :auto-width} grow-type) 10000 height)}

     [:& text-shape-edit-html {:shape shape}]]))

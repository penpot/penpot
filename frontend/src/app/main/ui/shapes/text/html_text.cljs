;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.text.html-text
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.text :as legacy.txt]
   [app.main.ui.shapes.text.styles :as sts]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(mf/defc render-text
  {::mf/wrap-props false}
  [props]
  (let [node   (obj/get props "node")
        parent (obj/get props "parent")
        shape  (obj/get props "shape")
        code?  (obj/get props "code?")
        text   (:text node)
        style  (if (= text "")
                 (sts/generate-text-styles shape parent)
                 (sts/generate-text-styles shape node))
        class  (when code? (:$id node))]
    [:span.text-node {:style style :class class}
     (if (= text "") "\u00A0" text)]))

(mf/defc render-root
  {::mf/wrap-props false}
  [props]
  (let [node     (obj/get props "node")
        children (obj/get props "children")
        shape    (obj/get props "shape")
        code?    (obj/get props "code?")
        style    (sts/generate-root-styles shape node code?)
        class  (when code? (:$id node))]
    [:div.root.rich-text
     {:style style
      :class class
      :xmlns "http://www.w3.org/1999/xhtml"}
     children]))

(mf/defc render-paragraph-set
  {::mf/wrap-props false}
  [props]
  (let [node     (obj/get props "node")
        children (obj/get props "children")
        shape    (obj/get props "shape")
        code?    (obj/get props "code?")
        style    (when-not code? (sts/generate-paragraph-set-styles shape))
        class    (when code? (:$id node))]
    [:div.paragraph-set {:style style :class class} children]))

(mf/defc render-paragraph
  {::mf/wrap-props false}
  [props]
  (let [node     (obj/get props "node")
        shape    (obj/get props "shape")
        children (obj/get props "children")
        code?    (obj/get props "code?")
        style    (when-not code? (sts/generate-paragraph-styles shape node))
        class    (when code? (:$id node))
        dir      (:text-direction node "auto")]
    [:p.paragraph {:style style :dir dir :class class} children]))

;; -- Text nodes
(mf/defc render-node
  {::mf/wrap-props false}
  [props]
  (let [{:keys [type text children] :as parent} (obj/get props "node")
        code? (obj/get props "code?")]
    (if (string? text)
      [:> render-text props]
      (let [component (case type
                        "root" render-root
                        "paragraph-set" render-paragraph-set
                        "paragraph" render-paragraph
                        nil)]
        (when component
          [:> component props
           (for [[index node] (d/enumerate children)]
             (let [props (-> (obj/clone props)
                             (obj/set! "node" node)
                             (obj/set! "parent" parent)
                             (obj/set! "index" index)
                             (obj/set! "key" index)
                             (obj/set! "code?" code?))]
               [:> render-node props]))])))))

(mf/defc text-shape
  {::mf/wrap-props false
   ::mf/forward-ref true}
  [props ref]
  (let [shape     (obj/get props "shape")
        grow-type (obj/get props "grow-type")
        code?     (obj/get props "code?")
        {:keys [id x y width height content]} shape

        content (if code? (legacy.txt/index-content content) content)

        style
        (when-not code?
          #js {:position "fixed"
               :left 0
               :top 0
               :background "white"
               :width  (if (#{:auto-width} grow-type) 100000 width)
               :height (if (#{:auto-height :auto-width} grow-type) 100000 height)})]

    [:div.text-node-html
     {:id (dm/str "html-text-node-" id)
      :ref ref
      :data-x x
      :data-y y
      :style style}
     ;; We use a class here because react has a bug that won't use the appropriate selector for
     ;; `background-clip`
     (when (not code?)
       [:style ".text-node { background-clip: text;
                             -webkit-background-clip: text; }"])
     [:& render-node {:index 0
                      :shape shape
                      :node content
                      :code? code?}]]))

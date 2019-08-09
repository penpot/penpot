;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.text
  (:require
   [cuerdas.core :as str]
   [goog.events :as events]
   [lentes.core :as l]
   [rumext.core :as mx]
   [rumext.alpha :as mf]
   [uxbox.main.data.shapes :as uds]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.shapes.attrs :as attrs]
   [uxbox.main.ui.shapes.common :as common]
   [uxbox.util.color :as color]
   [uxbox.util.data :refer [classnames normalize-props]]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.matrix :as gmt]))

;; --- Events

(defn handle-mouse-down
  [event {:keys [id group] :as shape} selected]
  (if (and (not (:blocked shape))
           (or @refs/selected-drawing-tool
               @refs/selected-edition))
    (dom/stop-propagation event)
    (common/on-mouse-down event shape selected)))

;; --- Text Component

(declare text-shape-html)
(declare text-shape-wrapper)
(declare text-shape-edit)

(mf/def text-component
  :mixins [mf/memo mf/reactive]
  :render
  (fn [own {:keys [shape] :as props}]
    (let [{:keys [id x1 y1 content group]} shape
          modifiers (mf/react (refs/selected-modifiers id))
          selected (mf/react refs/selected-shapes)
          edition? (= (mf/react refs/selected-edition) id)
          selected? (and (contains? selected id)
                         (= (count selected) 1))
          shape (assoc shape :modifiers modifiers)]
      (letfn [(on-mouse-down [event]
                (handle-mouse-down event shape selected))
              (on-double-click [event]
                ;; TODO: handle grouping event propagation
                ;; TODO: handle actions locking properly
                (dom/stop-propagation event)
                (st/emit! (udw/start-edition-mode id)))]
        [:g.shape {:class (when selected? "selected")
                   :on-double-click on-double-click
                   :on-mouse-down on-mouse-down}
         (if edition?
           [:& text-shape-edit {:shape shape}]
           [:& text-shape-wrapper {:shape shape}])]))))

;; --- Text Styles Helpers

(defn- make-style
  [{:keys [fill-color
           fill-opacity
           font-family
           font-weight
           font-style
           font-size
           text-align
           line-height
           letter-spacing
           user-select]
    :or {fill-color "#000000"
         fill-opacity 1
         font-family "sourcesanspro"
         font-weight "normal"
         font-style "normal"
         font-size 18
         line-height 1.4
         letter-spacing 1
         text-align "left"
         user-select false}
    :as shape}]
  (let [color (-> fill-color
                  (color/hex->rgba fill-opacity)
                  (color/rgb->str))]
    (merge
     {:fontSize (str font-size "px")
      :color color
      :whiteSpace "pre-wrap"
      :textAlign text-align
      :fontFamily font-family
      :fontWeight font-weight
      :fontStyle font-style}
     (when user-select {:userSelect "auto"})
     (when line-height {:lineHeight line-height})
     (when letter-spacing {:letterSpacing letter-spacing}))))

;; --- Text Shape Edit

(mf/def text-shape-edit
  :mixins [mf/memo]

  :init
  (fn [own props]
    (assoc own ::container (mf/create-ref)))

  :did-mount
  (fn [own]
    (let [shape (get-in own [::mf/props :shape])
          dom (mf/ref-node (::container own))]
      (set! (.-textContent dom) (:content shape ""))
      (.focus dom)
      own))

  :render
  (fn [own {:keys [shape] :as props}]
    (let [{:keys [id x1 y1 content width height]} (geom/size shape)
          style (make-style shape)
          on-input (fn [ev]
                     (let [content (dom/event->inner-text ev)]
                       (st/emit! (uds/update-text id content))))]
      [:foreignObject {:x x1 :y y1 :width width :height height}
       [:div {:style (normalize-props style)
              :ref (::container own)
              :on-input on-input
              :contentEditable true}]])))

;; --- Text Shape Wrapper

(mf/def text-shape-wrapper
  :mixins [mf/memo]

  :init
  (fn [own props]
    (assoc own ::fobject (mf/create-ref)))

  ;; NOTE: this is a hack for the browser rendering.
  ;;
  ;; Without forcing rerender, when the shape is displaced
  ;; and only x and y attrs are updated in dom, the whole content
  ;; of the foreignObject becomes sometimes partially or
  ;; completelly invisible. The complete dom rerender fixes that
  ;; problem.

  :did-mount
  (fn [own]
    (let [shape (get-in own [::mf/props :shape])
          dom (mf/ref-node (::fobject own))
          html (dom/render-to-html (text-shape-html shape))]
      (set! (.-innerHTML dom) html))
    own)

  :render
  (fn [own {:keys [shape] :as props}]
    (let [{:keys [id modifiers]} shape
          {:keys [displacement resize]} modifiers
          xfmt (cond-> (gmt/matrix)
                 displacement (gmt/multiply displacement)
                 resize (gmt/multiply resize))

          {:keys [x1 y1 width height] :as shape} (-> (geom/transform shape xfmt)
                                                     (geom/size))
          moving? (boolean displacement)]
      [:foreignObject {:x x1
                       :y y1
                       :class (classnames :move-cursor moving?)
                       :id (str id)
                       :ref (::fobject own)
                       :width width
                       :height height}])))

;; --- Text Shape Html

(mf/def text-shape-html
  :mixins [mf/memo]
  :render
  (fn [own {:keys [content] :as shape}]
    (let [style (make-style shape)]
      [:div {:style style} content])))

;; --- Text Shape Html

(mf/def text-shape
  :mixins [mf/memo]
  :key-fn pr-str

  :init
  (fn [own props]
    (assoc own ::fobject (mf/create-ref)))

  ;; NOTE: this is a hack for the browser rendering.
  ;;
  ;; Without forcing rerender, when the shape is displaced
  ;; and only x and y attrs are updated in dom, the whole content
  ;; of the foreignObject becomes sometimes partially or
  ;; completelly invisible. The complete dom rerender fixes that
  ;; problem.

  :did-mount
  (fn [own]
    (let [shape (::mf/props own)
          dom (mf/ref-node (::fobject own))
          html (dom/render-to-html (text-shape-html shape))]
      (set! (.-innerHTML dom) html))
    own)

  :render
  (fn [own {:keys [id content modifiers] :as shape}]
    (let [{:keys [displacement resize]} modifiers
          xfmt (cond-> (gmt/matrix)
                 displacement (gmt/multiply displacement)
                 resize (gmt/multiply resize))

          {:keys [x1 y1 width height] :as shape} (-> (geom/transform shape xfmt)
                                                     (geom/size))
          moving? (boolean displacement)
          style (make-style shape)]
      [:foreignObject {:x x1
                       :y y1
                       :class (classnames :move-cursor moving?)
                       :id (str id)
                       :ref (::fobject own)
                       :width width
                       :height height}
       [:div {:style style}
        [:p content]]])))

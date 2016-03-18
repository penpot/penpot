(ns uxbox.ui.shapes.text
  (:require [sablono.core :refer-macros [html]]
            [cuerdas.core :as str]
            [rum.core :as rum]
            [lentes.core :as l]
            [goog.events :as events]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.shapes :as ush]
            [uxbox.data.shapes :as ds]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.core :as uuc]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.keyboard :as kbd]
            [uxbox.ui.shapes.core :as uusc]
            [uxbox.ui.shapes.icon :as uusi]
            [uxbox.util.color :as color]
            [uxbox.util.dom :as dom])
  (:import goog.events.EventType))

(defn on-mouse-down
  [event own {:keys [id group] :as shape} selected]
  (let [selected? (contains? selected id)
        local (:rum/local own)]
    (when-not (:blocked shape)
      (cond
        (:edition @local)
        nil

        (and group (:locked (ush/resolve-parent shape)))
        nil

        (and (not selected?) (empty? selected))
        (do
          (dom/stop-propagation event)
          (uuc/acquire-action! "ui.shape.move")
          (rs/emit! (dw/select-shape id)))

        (and (not selected?) (not (empty? selected)))
        (do
          (dom/stop-propagation event)
          (if (kbd/shift? event)
            (rs/emit! (dw/select-shape id))
            (rs/emit! (dw/deselect-all)
                      (dw/select-shape id))))

        :else
        (do
          (dom/stop-propagation event)
          (uuc/acquire-action! "ui.shape.move"))))))

(defn on-mouse-up
  [event {:keys [id group] :as shape}]
  (cond
    (and group (:locked (ush/resolve-parent shape)))
    nil

    :else
    (do
      (dom/stop-propagation event)
      (uuc/release-action! "ui.shape"))))

(defn- text-component-did-mount
  [own]
  (letfn [(on-double-click [ev]
            (let [container (mx/get-ref-dom own "container")
                  local (:rum/local own)]
              (swap! local assoc :edition true)
              (uuc/acquire-action! "ui.text.edit")
              (set! (.-contentEditable container) true)
              (.setAttribute container "contenteditable" "true")
              (.focus container)))
          (on-blur [ev]
            (let [container (mx/get-ref-dom own "container")
                  local (:rum/local own)]
              (uuc/release-action! "ui.text.edit")
              (swap! local assoc :edition false)
              (set! (.-contentEditable container) false)
              (.removeAttribute container "contenteditable")))
          (on-input [ev]
            (let [content (dom/event->inner-text ev)
                  sid (:id (first (:rum/props own)))]
              (rs/emit! (ds/update-text sid {:content content}))))]
    (let [dom (mx/get-ref-dom own "main")
          dom2 (mx/get-ref-dom own "container")
          key1 (events/listen dom EventType.DBLCLICK on-double-click)
          key2 (events/listen dom2 EventType.BLUR on-blur)
          key3 (events/listen dom2 EventType.INPUT on-input)]
      (assoc own ::key1 key1 ::key2 key2 ::key3 key3))))

(defn- text-component-will-unmount
  [own]
  (let [key1 (::key1 own)
        key2 (::key2 own)
        key3 (::key3 own)]
    (events/unlistenByKey key1)
    (events/unlistenByKey key2)
    (events/unlistenByKey key3)
    (dissoc own ::key1 ::key2 ::key3)))

(defn- text-component-transfer-state
  [old-own own]
  (let [data (select-keys old-own [::key1 ::key2 ::key3])]
    (merge own data)))

(defn- text-component-render
  [own shape]
  (let [{:keys [id x1 y1 content group]} shape
        selected (rum/react uusc/selected-shapes-l)
        selected? (and (contains? selected id) (= (count selected) 1))
        on-mouse-down #(on-mouse-down % own shape selected)
        on-mouse-up #(on-mouse-up % shape)
        local (:rum/local own)]
    (html
     [:g.shape {:class (when selected? "selected")
                :ref "main"
                :on-mouse-down on-mouse-down
                :on-mouse-up on-mouse-up}
      (uusc/render-shape (assoc shape :editing? (:edition @local false)) nil)
      (when (and selected? (not (:edition @local false)))
        (uusi/handlers shape))])))

(def ^:const text-component
  (mx/component
   {:render text-component-render
    :name "text-componet"
    :did-mount text-component-did-mount
    :will-unmount text-component-will-unmount
    :transfer-state text-component-transfer-state
    :mixins [mx/static rum/reactive (mx/local)]}))

(defmethod uusc/render-component :builtin/text
  [own shape]
  (text-component shape))

(def ^:const +select-rect-attrs+
  {:stroke-dasharray "5,5"
   :style {:stroke "#333" :fill "transparent"
           :stroke-opacity "0.4"}})

(def ^:const +style-attrs+
  [:font-size])

(defn- build-style
  [{:keys [font fill opacity] :or {fill "#000000" opacity 1}}]
  (let [{:keys [family weight style size align line-height letter-spacing]
         :or {family "sourcesanspro"
              weight "normal"
              style "normal"
              line-height 1.4
              letter-spacing 1
              align "left"
              size 16}} font
        color (-> fill
                  (color/hex->rgba opacity)
                  (color/rgb->str))]
    (merge
     {:fontSize (str size "px")
      :color color
      :textAlign align
      :fontFamily family
      :fontWeight weight
      :fontStyle style}
     (when line-height {:lineHeight line-height})
     (when letter-spacing {:letterSpacing letter-spacing}))))

(defmethod uusc/render-shape :builtin/text
  [{:keys [id x1 y1 x2 y2 content drawing? editing?] :as shape}]
  (let [key (str id)
        rfm (ush/transformation shape)
        size (ush/size shape)
        props {:x x1 :y y1
               :transform (str rfm)}
        attrs (merge props size)
        style (build-style shape)]
    (html
     [:g
      (if (or drawing? editing?)
        [:g
         [:rect (merge attrs +select-rect-attrs+)]])
      [:foreignObject attrs
       [:p {:ref "container" :style style} content]]])))


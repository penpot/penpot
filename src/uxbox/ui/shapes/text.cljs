(ns uxbox.ui.shapes.text
  (:require [sablono.core :refer-macros [html]]
            [cuerdas.core :as str]
            [rum.core :as rum]
            [lentes.core :as l]
            [goog.events :as events]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.shapes :as ush]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.core :as uuc]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.keyboard :as kbd]
            [uxbox.ui.shapes.core :as uusc]
            [uxbox.ui.shapes.icon :as uusi]
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
          (uuc/acquire-action! :shape/movement)
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
          (uuc/acquire-action! :shape/movement))))))

(defn on-mouse-up
  [event {:keys [id group] :as shape}]
  (cond
    (and group (:locked (ush/resolve-parent shape)))
    nil

    :else
    (do
      (dom/stop-propagation event)
      (uuc/release-action! :draw/selrect)
      (uuc/release-action! :shape/movement))))

(defn- text-component-did-mount
  [own]
  (letfn [(on-double-click [ev]
            (let [container (mx/get-ref-dom own "container")
                  local (:rum/local own)]
              (swap! local assoc :edition true)
              (uuc/acquire-action! ::edition)
              (set! (.-contentEditable container) true)
              (.setAttribute container "contenteditable" "true")
              (.focus container)))
          (on-blur [ev]
            (let [container (mx/get-ref-dom own "container")
                  local (:rum/local own)]
              (uuc/release-action! ::edition)
              (swap! local assoc :edition false)
              (set! (.-contentEditable container) false)
              (.removeAttribute container "contenteditable")))]

    (let [dom (mx/get-ref-dom own "main")
          dom2 (mx/get-ref-dom own "container")
          key1 (events/listen dom EventType.DBLCLICK on-double-click)
          key2 (events/listen dom2 EventType.BLUR on-blur)]
      (assoc own ::key1 key1))))

(defn- text-component-will-unmount
  [own]
  (let [key1 (::key1 own)
        key2 (::key2 own)]
    (events/unlistenByKey key1)
    (events/unlistenByKey key2)
    (dissoc own ::key1 ::key2)))

(defn- text-component-transfer-state
  [old-own own]
  (let [data (select-keys old-own [::key1 ::key2])]
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
                ;; :on-double-click #(on-double-click own %)
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
  [{:keys [font-size]}]
  (merge {} (when font-size {:fontSize (str font-size "px")})))

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


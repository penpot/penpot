(ns uxbox.ui.workspace.colorpalette
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [beicon.core :as rx]
            [cats.labs.lens :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.library :as library]
            [uxbox.data.workspace :as dw]
            [uxbox.util.lens :as ul]
            [uxbox.util.data :refer (read-string)]
            [uxbox.util.color :refer (hex->rgb)]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.icons :as i]
            [uxbox.ui.keyboard :as kbd]
            [uxbox.util.dom :as dom]
            [uxbox.ui.mixins :as mx]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lenses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static ^:private collections-by-id-l
  (-> (comp (l/in [:colors-by-id])
            (ul/merge library/+color-collections-by-id+))
      (l/focus-atom st/state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- select-collection
  [local event]
  (let [value (-> (dom/event->value event)
                  (read-string))]
    (swap! local assoc :selected value)))

(defn- select-color
  [color event]
  (dom/prevent-default event)
  (if (kbd/shift? event)
    (rs/emit! (dw/update-selected-shapes-stroke {:color color}))
    (rs/emit! (dw/update-selected-shapes-fill {:color color}))))

(defn- colorpalette-render
  [own]
  (let [local (:rum/local own)
        flags (rum/react wb/flags-l)
        collections-by-id (rum/react collections-by-id-l)
        collections (sort-by :name (vals collections-by-id))
        collection (if-let [collid (:selected @local)]
                     (get collections-by-id collid)
                     (first collections))
        select-collection #(select-collection local %)
        close #(rs/emit! (dw/toggle-tool :workspace/colorpalette))]
    (when (contains? flags :workspace/colorpalette)
      (html
       [:div.color-palette
        [:div.color-palette-actions
         [:select.input-select {:on-change select-collection}
          (for [collection collections]
            [:option {:key (str (:id collection))
                      :value (pr-str (:id collection))}
             (:name collection)])]
         #_[:div.color-palette-buttons
            [:div.btn-palette.edit.current i/pencil]
            [:div.btn-palette.create i/close]]]
        [:span.left-arrow i/arrow-slide]
        [:div.color-palette-content
         (for [hex-color (:colors collection)
               :let [rgb-vec (hex->rgb hex-color)
                     rgb-color (apply str "" (interpose ", " rgb-vec))]]
           [:div.color-cell {:key (str hex-color)
                             :on-click #(select-color hex-color %)}
            [:span.color {:style {:background hex-color}}]
            [:span.color-text hex-color]
            [:span.color-text rgb-color]])]

        [:span.right-arrow i/arrow-slide]
        [:span.close-palette {:on-click close}
         i/close]]))))

(def ^:static colorpalette
  (mx/component
   {:render colorpalette-render
    :name "colorpalette"
    :mixins [mx/static rum/reactive
             (mx/local {})]}))

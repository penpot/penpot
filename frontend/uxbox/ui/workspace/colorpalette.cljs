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
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.icons :as i]
            [uxbox.ui.dom :as dom]
            [uxbox.ui.util :as util]
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
  (rs/emit! (dw/update-selected-shapes-fill {:fill color})))

(defn- colorpalette-render
  [own]
  (let [local (:rum/local own)
        flags (rum/react wb/flags-l)
        collections-by-id (rum/react collections-by-id-l)
        collections (vals collections-by-id)
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
          (for [collection (vals collections-by-id)
                :let [_ (println collection)]]
            [:option {:key (str (:id collection))
                      :value (pr-str (:id collection))}
             (:name collection)])]
         #_[:div.color-palette-buttons
            [:div.btn-palette.edit.current i/pencil]
            [:div.btn-palette.create i/close]]]
        [:span.left-arrow i/arrow-slide]
        [:div.color-palette-content
         (for [hex-color (:colors collection)
               :let [rgb-vec (util/hex->rgb hex-color)
                     rgb-color (apply str "" (interpose ", " rgb-vec))]]
           [:div.color-cell {:key (str hex-color)
                             :on-click #(select-color hex-color %)}
            [:span.color {:style {:background hex-color}}]
            [:span.color-text hex-color]
            [:span.color-text rgb-color]])]

        [:span.right-arrow i/arrow-slide]
        [:span.close-palette {:on-click close}
         i/close]]))))

;; [:div.color-cell.current
;;  [:span.color {:style {:background "#dddddd"}}]
;;  [:span.color-text "#121212"]
;;  [:span.color-text "rgb 21,21,21"]]
;; [:div.color-cell
;;  [:span.color {:style {:background "#c4c4c4"}}]
;;  [:span.color-text "#121212"]
;;  [:span.color-text "rgb 21,21,21"]]
;; [:div.color-cell
;;  [:span.color {:style {:background "#909090"}}]
;;  [:span.color-text "#121212"]
;;  [:span.color-text "rgb 21,21,21"]]
;; [:div.color-cell
;;  [:span.color {:style {:background "#4f4f4f"}}]
;;  [:span.color-text "#121212"]
;;  [:span.color-text "rgb 21,21,21"]]
;; [:div.color-cell
;;  [:span.color {:style {:background "#8ce2b6"}}]
;;  [:span.color-text "#121212"]
;;  [:span.color-text "rgb 21,21,21"]]
;; [:div.color-cell
;;  [:span.color {:style {:background "#b8de71"}}]
;;  [:span.color-text "#121212"]
;;  [:span.color-text "rgb 21,21,21"]]
;; [:div.color-cell
;;  [:span.color {:style {:background "#a784e0"}}]
;;  [:span.color-text "#121212"]
;;  [:span.color-text "rgb 21,21,21"]]
;; [:div.color-cell
;;  [:span.color {:style {:background "#e49ce2"}}]
;;  [:span.color-text "#121212"]
;;  [:span.color-text "rgb 21,21,21"]]
;; [:div.color-cell
;;  [:span.color {:style {:background "#d97950"}}]
;;  [:span.color-text "#121212"]
;;  [:span.color-text "rgb 21,21,21"]]
;; [:div.color-cell
;;  [:span.color {:style {:background "#e73232"}}]
;;  [:span.color-text "#121212"]
;;  [:span.color-text "rgb 21,21,21"]]
;; [:div.color-cell
;;  [:span.color {:style {:background "#ebcd2f"}}]
;;  [:span.color-text "#121212"]
;;  [:span.color-text "rgb 21,21,21"]]
;; [:div.color-cell
;;  [:span.color {:style {:background "#6869de"}}]
;;  [:span.color-text "#121212"]
;;  [:span.color-text "rgb 21,21,21"]]
;; [:div.color-cell
;;  [:span.color {:style {:background "#5ad5d9"}}]
;;  [:span.color-text "#121212"]
;;  [:span.color-text "rgb 21,21,21"]
;;  [:div.color-tooltip
;;   [:div.row-flex
;;    [:input.input-text {:type "text" :placeholder "#Hex"}]
;;    [:input.input-text {:type "text" :placeholder "RGB"}]
;;    ]]]
;; [:div.color-cell.add-color
;;  [:span.color i/close]
;;  [:span.color-text "+ Add color"]]]

(def ^:static colorpalette
  (util/component
   {:render colorpalette-render
    :name "colorpalette"
    :mixins [mx/static rum/reactive
             (mx/local {})]}))

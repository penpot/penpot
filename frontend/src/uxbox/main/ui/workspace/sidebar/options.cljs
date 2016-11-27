;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.options
  (:require
   [lentes.core :as l]
   [uxbox.util.i18n :refer (tr)]
   [uxbox.util.router :as r]
   [potok.core :as ptk]
   [uxbox.store :as st]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.data.shapes :as uds]
   [uxbox.main.ui.workspace.base :as wb]
   [uxbox.main.ui.icons :as i]
   [uxbox.util.mixins :as mx :include-macros true]
   [uxbox.main.ui.workspace.colorpicker :refer (colorpicker)]
   [uxbox.main.ui.workspace.recent-colors :refer (recent-colors)]
   [uxbox.main.ui.workspace.sidebar.options.icon-measures :as options-iconm]
   [uxbox.main.ui.workspace.sidebar.options.circle-measures :as options-circlem]
   [uxbox.main.ui.workspace.sidebar.options.rect-measures :as options-rectm]
   [uxbox.main.ui.workspace.sidebar.options.line-measures :as options-linem]
   [uxbox.main.ui.workspace.sidebar.options.fill :as options-fill]
   [uxbox.main.ui.workspace.sidebar.options.text :as options-text]
   [uxbox.main.ui.workspace.sidebar.options.stroke :as options-stroke]
   [uxbox.main.ui.workspace.sidebar.options.page :as options-page]
   [uxbox.main.ui.workspace.sidebar.options.interactions :as options-interactions]
   [uxbox.main.geom :as geom]
   [uxbox.util.dom :as dom]
   [uxbox.util.data :as data]))

;; --- Constants

(def ^:private +menus-map+
  {:icon [::icon-measures ::fill ::stroke ::interactions]
   :rect [::rect-measures ::fill ::stroke ::interactions]
   :line [::line-measures ::stroke ::interactions]
   :path [::fill ::stroke ::interactions]
   :circle [::circle-measures ::fill ::stroke ::interactions]
   :text [::fill ::text ::interactions]
   :image [::interactions]
   :group [::interactions]
   ::page [::page-measures ::page-grid-options]})

(def ^:private +menus+
  [{:name "Size, position & rotation"
    :id ::icon-measures
    :icon i/infocard
    :comp options-iconm/icon-measures-menu}
   {:name "Size, position & rotation"
    :id ::rect-measures
    :icon i/infocard
    :comp options-rectm/rect-measures-menu}
   {:name "Size, position & rotation"
    :id ::line-measures
    :icon i/infocard
    :comp options-linem/line-measures-menu}
   {:name "Size, position & rotation"
    :id ::circle-measures
    :icon i/infocard
    :comp options-circlem/circle-measures-menu}
   {:name "Fill"
    :id ::fill
    :icon i/fill
    :comp options-fill/fill-menu}
   {:name "Stroke"
    :id ::stroke
    :icon i/stroke
    :comp options-stroke/stroke-menu}
   {:name "Text"
    :id ::text
    :icon i/text
    :comp options-text/text-menu}
   {:name "Interactions"
    :id ::interactions
    :icon i/action
    :comp options-interactions/interactions-menu}
   {:name "Page settings"
    :id ::page-measures
    :icon i/page
    :comp options-page/measures-menu}
   {:name "Grid settings"
    :id ::page-grid-options
    :icon i/grid
    :comp options-page/grid-options-menu}])

(def ^:private +menus-by-id+
  (data/index-by-id +menus+))

;; --- Options

(defn- options-did-remount
  [old-own own]
  (let [[prev-shape] (:rum/args old-own)
        [curr-shape] (:rum/args own)]
    (when (not (identical? prev-shape curr-shape))
      (reset! (:rum/local own) {}))
    own))

(mx/defcs options
  {:mixins [mx/static (mx/local)]
   :did-remount options-did-remount}
  [{:keys [rum/local] :as own} shape]
  (let [menus (get +menus-map+ (:type shape ::page))
        contained-in? (into #{} menus)
        active (:menu @local (first menus))]
    (println "options" active)
    [:div
     [:ul.element-icons
      (for [menu-id (get +menus-map+ (:type shape ::page))
            :let [menu (get +menus-by-id+ menu-id)
                  selected? (= active menu-id)]]
        [:li#e-info {:on-click #(swap! local assoc :menu menu-id)
                     :key (str "menu-" (:id menu))
                     :class (when selected? "selected")}
         (:icon menu)])]
     (when-let [menu (get +menus-by-id+ active)]
       ((:comp menu) menu shape))]))

(def selected-shape-ref
  (letfn [(getter [state]
            (let [selected (get-in state [:workspace :selected])]
              (when (= 1 (count selected))
                (get-in state [:shapes (first selected)]))))]
    (-> (l/lens getter)
        (l/derive st/state))))

(mx/defc options-toolbox
  {:mixins [mx/static mx/reactive]}
  []
  (let [shape (mx/react selected-shape-ref)
        close #(st/emit! (udw/toggle-flag :element-options))]
    [:div.elementa-options.tool-window
     [:div.tool-window-bar
      [:div.tool-window-icon i/options]
      [:span (tr "ds.element-options")]
      [:div.tool-window-close {:on-click close} i/close]]
     [:div.tool-window-content
      [:div.element-options
       (options shape)]]]))

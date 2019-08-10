;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.options
  (:require
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [rumext.core :as mx]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.shapes :as uds]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.geom :as geom]
   [uxbox.main.store :as st]
   [uxbox.main.ui.shapes.attrs :refer [shape-default-attrs]]
   [uxbox.main.ui.workspace.sidebar.options.circle-measures :as options-circlem]
   [uxbox.main.ui.workspace.sidebar.options.fill :as options-fill]
   [uxbox.main.ui.workspace.sidebar.options.icon-measures :as options-iconm]
   [uxbox.main.ui.workspace.sidebar.options.image-measures :as options-imagem]
   [uxbox.main.ui.workspace.sidebar.options.interactions :as options-interactions]
   [uxbox.main.ui.workspace.sidebar.options.page :as options-page]
   [uxbox.main.ui.workspace.sidebar.options.rect-measures :as options-rectm]
   [uxbox.main.ui.workspace.sidebar.options.stroke :as options-stroke]
   [uxbox.main.ui.workspace.sidebar.options.text :as options-text]
   [uxbox.util.data :as data]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :refer [tr]]))

;; --- Constants

(def ^:private +menus-map+
  {:icon   [::icon-measures ::fill ::stroke]
   :rect   [::rect-measures ::fill ::stroke]
   :path   [::fill ::stroke ::interactions]
   :circle [::circle-measures ::fill ::stroke]
   :text   [::fill ::text]
   :image  [::image-measures]
   ::page  [::page-measures ::page-grid-options]})

(def ^:private +menus+
  [{:name (tr "element.measures")
    :id ::icon-measures
    :icon i/infocard
    :comp options-iconm/icon-measures-menu}
   {:name (tr "element.measures")
    :id ::image-measures
    :icon i/infocard
    :comp options-imagem/image-measures-menu}
   {:name (tr "element.measures")
    :id ::rect-measures
    :icon i/infocard
    :comp options-rectm/rect-measures-menu}
   {:name (tr "element.measures")
    :id ::circle-measures
    :icon i/infocard
    :comp options-circlem/circle-measures-menu}
   {:name (tr "element.fill")
    :id ::fill
    :icon i/fill
    :comp options-fill/fill-menu}
   {:name (tr "element.fill")
    :id ::stroke
    :icon i/stroke
    :comp options-stroke/stroke-menu}
   {:name (tr "element.text")
    :id ::text
    :icon i/text
    :comp options-text/text-menu}
   {:name (tr "element.interactions")
    :id ::interactions
    :icon i/action
    :comp options-interactions/interactions-menu}
   {:name (tr "element.page-measures")
    :id ::page-measures
    :icon i/page
    :comp options-page/measures-menu}
   {:name (tr "element.page-grid-options")
    :id ::page-grid-options
    :icon i/grid
    :comp options-page/grid-options-menu}])

(def ^:private +menus-by-id+
  (data/index-by-id +menus+))

;; --- Options

(mf/defc shape-options
  [{:keys [sid] :as props}]
  (let [shape-iref (mf/use-memo {:deps sid
                                 :init #(-> (l/in [:shapes sid])
                                            (l/derive st/state))})
        shape (mf/deref shape-iref)
        menus (get +menus-map+ (:type shape))]
    [:div
     (for [mid menus]
       (let [{:keys [comp] :as menu} (get +menus-by-id+ mid)]
         [:& comp {:menu menu :shape shape :key mid}]))]))

(mf/defc page-options
  [{:keys [page] :as props}]
  (let [menus (get +menus-map+ ::page)]
    [:div
     (for [mid menus]
       (let [{:keys [comp] :as menu} (get +menus-by-id+ mid)]
         [:& comp {:menu menu :page page :key mid}]))]))

(mf/defc options-toolbox
  {:wrap [mf/wrap-memo]}
  [{:keys [page selected] :as props}]
  (let [close #(st/emit! (udw/toggle-flag :element-options))]
    [:div.elementa-options.tool-window
     [:div.tool-window-bar
      [:div.tool-window-icon i/options]
      [:span (tr "ds.element-options")]
      [:div.tool-window-close {:on-click close} i/close]]
     [:div.tool-window-content
      [:div.element-options
       (if (= (count selected) 1)
         [:& shape-options {:sid (first selected)}]
         [:& page-options {:page page}])]]]))

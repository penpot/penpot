;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.icons
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.util.router :as r]
            [uxbox.util.rstore :as rs]
            [uxbox.main.state :as st]
            [uxbox.main.library :as library]
            [uxbox.main.data.workspace :as dw]
            [uxbox.main.ui.shapes.icon :as icon]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.mixins :as mx]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (read-string)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lenses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private drawing-shape
  "A focused vision of the drawing property
  of the workspace status. This avoids
  rerender the whole toolbox on each workspace
  change."
  (as-> (l/in [:workspace :drawing]) $
    (l/derive $ st/state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Icons
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- select-icon
  [icon]
  (rs/emit! (dw/select-for-drawing icon)))

(defn- change-icon-coll
  [local event]
  (let [value (-> (dom/event->value event)
                  (read-string))]
    (swap! local assoc :collid value)
    (rs/emit! (dw/select-for-drawing nil))))

(defn- icon-wrapper-render
  [own icon]
  (icon/icon-svg icon))

(def ^:private icon-wrapper
  (mx/component
   {:render icon-wrapper-render
    :name "icon-wrapper"
    :mixins [mx/static]}))

(defn icons-render
  [own]
  (let [local (:rum/local own)
        drawing (mx/react drawing-shape)
        collid (:collid @local)
        icons (get-in library/+icon-collections-by-id+ [collid :icons])
        on-close #(rs/emit! (dw/toggle-flag :icons))
        on-select #(select-icon %)
        on-change #(change-icon-coll local %)]
    (html
     [:div#form-figures.tool-window
      [:div.tool-window-bar
       [:div.tool-window-icon i/icon-set]
       [:span "Icons"]
       [:div.tool-window-close {:on-click on-close} i/close]]
      [:div.tool-window-content
       [:div.figures-catalog
        ;; extract component: set selector
        [:select.input-select.small {:on-change on-change
                                     :value collid}
         (for [icon-coll library/+icon-collections+]
           [:option {:key (str "icon-coll" (:id icon-coll))
                     :value (pr-str (:id icon-coll))}
            (:name icon-coll)])]]
       (for [icon icons
             :let [selected? (= drawing icon)]]
         [:div.figure-btn {:key (str (:id icon))
                           :class (when selected? "selected")
                           :on-click #(on-select icon)}
          (icon-wrapper icon)])]])))

(def icons-toolbox
  (mx/component
   {:render icons-render
    :name "icons-toolbox"
    :mixins [mx/reactive
             (mx/local {:collid 1 :builtin true})]}))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.icons
  (:require [lentes.core :as l]
            [uxbox.util.router :as r]
            [potok.core :as ptk]
            [uxbox.store :as st]
            [uxbox.main.data.workspace :as dw]
            [uxbox.main.data.icons :as udi]
            [uxbox.main.ui.shapes.icon :as icon]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.main.ui.dashboard.icons :as icons]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (read-string)]))

;; --- Refs

(def ^:private drawing-shape
  "A focused vision of the drawing property
  of the workspace status. This avoids
  rerender the whole toolbox on each workspace
  change."
  (-> (l/in [:workspace :drawing])
      (l/derive st/state)))

;; --- Icons (Component)

(mx/defc icon-wrapper
  {:mixins [mx/static]}
  [icon]
  (icon/icon-svg icon))

(defn- icons-toolbox-will-mount
  [own]
  (let [local (:rum/local own)]
    (st/emit! (udi/fetch-collections))
    (st/emit! (udi/fetch-icons nil))
    (add-watch local ::key (fn [_ _ _ {:keys [id]}]
                             (st/emit! (udi/fetch-icons id))))
    own))

(defn- icons-toolbox-will-unmount
  [own]
  (let [local (:rum/local own)]
    (remove-watch local ::key)
    own))

(mx/defcs icons-toolbox
  {:mixins [(mx/local) mx/reactive]
   :will-mount icons-toolbox-will-mount
   :will-unmount icons-toolbox-will-unmount}
  [{:keys [rum/local] :as own}]
  (let [drawing (mx/react drawing-shape)

        colls-map (mx/react icons/collections-ref)
        colls (->> (vals colls-map)
                   (sort-by :name))
        coll (get colls-map (:id @local))

        icons (mx/react icons/icons-ref)
        icons (->> (vals icons)
                   (filter #(= (:id coll) (:collection %))))]

    (letfn [(on-close [event]
              (st/emit! (dw/toggle-flag :icons)))
            (on-select [icon event]
              (st/emit! (dw/select-for-drawing icon)))
            (on-change [event]
              (let [value (-> (dom/event->value event)
                              (read-string))]
                (swap! local assoc :id value)
                (st/emit! (dw/select-for-drawing nil))))]
      [:div#form-figures.tool-window
       [:div.tool-window-bar
        [:div.tool-window-icon i/icon-set]
        [:span "Icons"]
        [:div.tool-window-close {:on-click on-close} i/close]]
       [:div.tool-window-content
        [:div.figures-catalog
         ;; extract component: set selector
         [:select.input-select.small {:on-change on-change
                                      :value (pr-str (:id coll))}
          [:option {:value (pr-str nil)} "Storage"]
          (for [coll colls]
            [:option {:key (str "icon-coll" (:id coll))
                      :value (pr-str (:id coll))}
             (:name coll)])]]
        (for [icon icons
              :let [selected? (= drawing icon)]]
          [:div.figure-btn {:key (str (:id icon))
                            :class (when selected? "selected")
                            :on-click (partial on-select icon)}
           (icon-wrapper icon)])]])))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.workspace.sidebar.drawtools
  (:require
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.uuid :as uuid]))

;; --- Constants

(def +draw-tools+
  [{:icon i/box
    :help "ds.help.rect"
    :type :rect
    :priority 1}
   {:icon i/circle
    :help "ds.help.circle"
    :type :circle
    :priority 2}
   {:icon i/text
    :help "ds.help.text"
    :type :text
    :priority 4}
   {:icon i/curve
    :help "ds.help.path"
    :type :path
    :priority 5}
   {:icon i/pencil
    :help "ds.help.curve"
    :type :curve
    :priority 6}
   ;; TODO: we need an icon for canvas creation
   {:icon i/box
    :help "ds.help.canvas"
    :type :canvas
    :priority 7}])

;; --- Draw Toolbox (Component)

(mf/defc draw-toolbox
  {:wrap [mf/wrap-memo]}
  [{:keys [flags] :as props}]
  (letfn [(close [event]
            (st/emit! (dw/deactivate-flag :drawtools)))
          (select [event tool]
            (st/emit! :interrupt
                      (dw/deactivate-ruler)
                      (dw/select-for-drawing tool)))
          (toggle-ruler [event]
            (st/emit! (dw/select-for-drawing nil)
                      (dw/deselect-all)
                      (dw/toggle-ruler)))]

    (let [selected (mf/deref refs/selected-drawing-tool)
          tools (sort-by (comp :priority second) +draw-tools+)]
      [:div.tool-window.drawing-tools
       [:div.tool-window-bar
        [:div.tool-window-icon i/window]
        [:span (tr "ds.draw-tools")]
        [:div.tool-window-close {:on-click close} i/close]]
       [:div.tool-window-content
        (for [item tools]
          (let [selected? (= (:type item) selected)]
            [:div.tool-btn.tooltip.tooltip-hover
             {:alt (tr (:help item))
              :class (when selected? "selected")
              :key (:type item)
              :on-click #(select % (:type item))}
             (:icon item)]))

        #_[:div.tool-btn.tooltip.tooltip-hover
           {:alt (tr "ds.help.ruler")
            :on-click toggle-ruler
            :class (when (contains? flags :ruler) "selected")}
           i/ruler-tool]]])))


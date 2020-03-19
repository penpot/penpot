;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.context-menu
  "A workspace specific context menu (mouse right click)."
  (:require
   [beicon.core :as rx]
   [lentes.core :as l]
   [potok.core :as ptk]
   [rumext.alpha :as mf]
   [uxbox.main.store :as st]
   [uxbox.main.refs :as refs]
   [uxbox.main.streams :as ms]
   [uxbox.builtins.icons :as i]
   [uxbox.util.dom :as dom]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.ui.react-hooks :refer [use-rxsub]]
   [uxbox.main.ui.components.dropdown :refer [dropdown]]))

(def menu-ref
  (-> (l/key :context-menu)
      (l/derive refs/workspace-local)))

(defn- prevent-default
  [event]
  (dom/prevent-default event)
  (dom/stop-propagation event))

(mf/defc shape-context-menu
  [{:keys [mdata] :as props}]
  (let [shape (:shape mdata)
        selected (:selected mdata)

        on-duplicate
        (fn [event]
          (st/emit! dw/duplicate-selected))

        on-delete
        (fn [event]
          (st/emit! dw/delete-selected)) ]
    [:*
     [:li {:on-click on-duplicate} i/copy [:span "duplicate"]]
     [:li {:on-click on-delete} i/trash [:span "delete"]]]))

(mf/defc viewport-context-menu
  [{:keys [mdata] :as props}]
  [:*
   [:li i/copy [:span "paste (TODO)"]]
   [:li i/copy [:span "copy as svg (TODO)"]]])

(mf/defc context-menu
  [props]
  (let [mdata (mf/deref menu-ref)]
    [:& dropdown {:show (boolean mdata)
                  :on-close #(st/emit! dw/hide-context-menu)}
     [:ul.workspace-context-menu
      {:style {:top (- (get-in mdata [:position :y]) 20)
               :left (get-in mdata [:position :x])}
       :on-context-menu prevent-default}

      (if (:shape mdata)
        [:& shape-context-menu {:mdata mdata}]
        [:& viewport-context-menu {:mdata mdata}])]]))




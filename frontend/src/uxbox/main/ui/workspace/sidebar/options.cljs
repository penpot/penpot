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
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.store :as st]
   [uxbox.main.refs :as refs]
   [uxbox.main.ui.workspace.sidebar.options.rect :as rect]
   [uxbox.main.ui.workspace.sidebar.options.circle :as circle]
   [uxbox.main.ui.workspace.sidebar.options.path :as path]
   [uxbox.main.ui.workspace.sidebar.options.image :as image]
   [uxbox.main.ui.workspace.sidebar.options.page :as page]
   [uxbox.util.i18n :refer [tr]]))

;; --- Constants

;; (def ^:private +menus-map+
;;   {:icon   [::icon-measures ::fill ::stroke]
;;    :rect   [::rect-measures ::fill ::stroke]
;;    :path   [::fill ::stroke ::interactions]
;;    :circle [::circle-measures ::fill ::stroke]
;;    :text   [::fill ::text]
;;    :image  [::image-measures]
;;    ::page  [::page-measures ::page-grid-options]})

;; --- Options

(mf/defc shape-options
  [{:keys [shape-id] :as props}]
  (let [shape-iref (mf/use-memo {:deps #js [(str shape-id)]
                                 :fn #(-> (l/in [:workspace-data :shapes-by-id shape-id])
                                          (l/derive st/state))})
        shape (mf/deref shape-iref)]
    [:div
     (case (:type shape)
       :rect [:& rect/options {:shape shape}]
       :circle [:& circle/options {:shape shape}]
       :path [:& path/options {:shape shape}]
       :curve [:& path/options {:shape shape}]
       :image [:& image/options {:shape shape}]
       nil)]))

(mf/defc options-toolbox
  {:wrap [mf/wrap-memo]}
  [{:keys [page selected] :as props}]
  (let [close #(st/emit! (udw/toggle-layout-flag :element-options))
        selected (mf/deref refs/selected-shapes)]
    [:div.elementa-options.tool-window
     ;; [:div.tool-window-bar
     ;;  [:div.tool-window-icon i/options]
     ;;  [:span (tr "ds.settings.element-options")]
     ;;  [:div.tool-window-close {:on-click close} i/close]]
     [:div.tool-window-content
      [:div.element-options
       (if (= (count selected) 1)
         [:& shape-options {:shape-id (first selected)}]
         [:& page/options {:page page}])]]]))

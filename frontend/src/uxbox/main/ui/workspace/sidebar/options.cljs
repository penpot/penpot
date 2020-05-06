;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.sidebar.options
  (:require
   [okulary.core :as l]
   [rumext.alpha :as mf]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.store :as st]
   [uxbox.main.refs :as refs]
   [uxbox.main.ui.workspace.sidebar.align :refer [align-options]]
   [uxbox.main.ui.workspace.sidebar.options.frame :as frame]
   [uxbox.main.ui.workspace.sidebar.options.group :as group]
   [uxbox.main.ui.workspace.sidebar.options.rect :as rect]
   [uxbox.main.ui.workspace.sidebar.options.icon :as icon]
   [uxbox.main.ui.workspace.sidebar.options.circle :as circle]
   [uxbox.main.ui.workspace.sidebar.options.path :as path]
   [uxbox.main.ui.workspace.sidebar.options.image :as image]
   [uxbox.main.ui.workspace.sidebar.options.text :as text]
   [uxbox.main.ui.workspace.sidebar.options.page :as page]
   [uxbox.main.ui.workspace.sidebar.options.interactions :refer [interactions-menu]]
   [uxbox.main.ui.components.tab-container :refer [tab-container tab-element]]
   [uxbox.util.i18n :refer [tr]]))

;; --- Options

(mf/defc shape-options
  {::mf/wrap [#(mf/throttle % 60)]}
  [{:keys [shape] :as props}]
  [:div
   (case (:type shape)
     :frame [:& frame/options {:shape shape}]
     :group [:& group/options {:shape shape}]
     :text [:& text/options {:shape shape}]
     :rect [:& rect/options {:shape shape}]
     :icon [:& icon/options {:shape shape}]
     :circle [:& circle/options {:shape shape}]
     :path [:& path/options {:shape shape}]
     :curve [:& path/options {:shape shape}]
     :image [:& image/options {:shape shape}]
     nil)])

(mf/defc options-toolbox
  {:wrap [mf/memo]}
  [{:keys [page selected] :as props}]
  (let [close #(st/emit! (udw/toggle-layout-flag :element-options))
        on-change-tab #(st/emit! (udw/set-options-mode %))

        options-mode (mf/deref refs/options-mode)

        selected (mf/deref refs/selected-shapes)
        shape-id (first selected)
        page-id (:id page)
        shape-iref (-> (mf/deps shape-id page-id)
                       (mf/use-memo
                        #(-> (l/in [:objects shape-id])
                             (l/derived refs/workspace-data))))
        shape (mf/deref shape-iref)]

    [:div.tool-window
      ;; [:div.tool-window-bar
      ;;  [:div.tool-window-icon i/options]
      ;;  [:span (tr "ds.settings.element-options")]
      ;;  [:div.tool-window-close {:on-click close} i/close]]
      [:div.tool-window-content
        [:& tab-container {:on-change-tab on-change-tab :selected options-mode}

         [:& tab-element
          {:id :design :title (tr "workspace.options.design")}
          [:div.element-options
           [:& align-options]
           [:div
            (if (= (count selected) 1)
              [:& shape-options {:shape shape}]
              [:& page/options {:page page}])]]]

         [:& tab-element
          {:id :prototype :title (tr "workspace.options.prototype")}
          [:div.element-options
           [:& interactions-menu {:shape shape}]]]]
         ]]))


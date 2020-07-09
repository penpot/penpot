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
   [beicon.core :as rx]
   [rumext.alpha :as mf]
   [uxbox.common.spec :as us]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.components.tab-container :refer [tab-container tab-element]]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.ui.workspace.sidebar.align :refer [align-options]]
   [uxbox.main.ui.workspace.sidebar.options.circle :as circle]
   [uxbox.main.ui.workspace.sidebar.options.exports :refer [exports-menu]]
   [uxbox.main.ui.workspace.sidebar.options.frame :as frame]
   [uxbox.main.ui.workspace.sidebar.options.group :as group]
   [uxbox.main.ui.workspace.sidebar.options.icon :as icon]
   [uxbox.main.ui.workspace.sidebar.options.image :as image]
   [uxbox.main.ui.workspace.sidebar.options.text :as text]
   [uxbox.main.ui.workspace.sidebar.options.page :as page]
   [uxbox.main.ui.workspace.sidebar.options.multiple :as multiple]
   [uxbox.main.ui.workspace.sidebar.options.interactions :refer [interactions-menu]]
   [uxbox.main.ui.workspace.sidebar.options.path :as path]
   [uxbox.main.ui.workspace.sidebar.options.rect :as rect]
   [uxbox.main.ui.workspace.sidebar.options.text :as text]
   [uxbox.util.i18n :as i18n :refer [tr t]]))

;; --- Options

(mf/defc shape-options
  {::mf/wrap [#(mf/throttle % 60)]}
  [{:keys [shape shapes-with-children page] :as props}]
  [:*
   (case (:type shape)
     :frame [:& frame/options {:shape shape}]
     :group [:& group/options {:shape shape :shape-with-children shapes-with-children}]
     :text [:& text/options {:shape shape}]
     :rect [:& rect/options {:shape shape}]
     :icon [:& icon/options {:shape shape}]
     :circle [:& circle/options {:shape shape}]
     :path [:& path/options {:shape shape}]
     :curve [:& path/options {:shape shape}]
     :image [:& image/options {:shape shape}]
     nil)
   [:& exports-menu {:shape shape :page page}]])


(mf/defc options-content
  {::mf/wrap [mf/memo]}
  [{:keys [section shapes shapes-with-children page] :as props}]
  (let [locale (mf/deref i18n/locale)]
    [:div.tool-window
     [:div.tool-window-content
      [:& tab-container {:on-change-tab #(st/emit! (udw/set-options-mode %))
                         :selected section}
       [:& tab-element {:id :design
                        :title (t locale "workspace.options.design")}
        [:div.element-options
         [:& align-options]
         (case (count shapes)
           0 [:& page/options {:page page}]
           1 [:& shape-options {:shape (first shapes) :shapes-with-children shapes-with-children}]
           [:& multiple/options {:shapes shapes-with-children}])]]

       [:& tab-element {:id :prototype
                        :title (t locale "workspace.options.prototype")}
        [:div.element-options
         [:& interactions-menu {:shape (first shapes)}]]]]]]))


(mf/defc options-toolbox
  {::mf/wrap [mf/memo]}
  [{:keys [page local] :as props}]
  (let [section              (:options-mode local)
        shapes               (mf/deref refs/selected-objects)
        shapes-with-children (mf/deref refs/selected-objects-with-children)]
    [:& options-content {:shapes shapes
                         :shapes-with-children shapes-with-children
                         :page page
                         :section section}]))


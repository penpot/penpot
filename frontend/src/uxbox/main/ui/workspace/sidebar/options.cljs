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
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [uxbox.common.spec :as us]
   [uxbox.main.data.messages :as dm]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.components.tab-container :refer [tab-container tab-element]]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.ui.workspace.sidebar.align :refer [align-options]]
   [uxbox.main.ui.workspace.sidebar.options.circle :as circle]
   [uxbox.main.ui.workspace.sidebar.options.frame :as frame]
   [uxbox.main.ui.workspace.sidebar.options.group :as group]
   [uxbox.main.ui.workspace.sidebar.options.icon :as icon]
   [uxbox.main.ui.workspace.sidebar.options.image :as image]
   [uxbox.main.ui.workspace.sidebar.options.interactions :refer [interactions-menu]]
   [uxbox.main.ui.workspace.sidebar.options.page :as page]
   [uxbox.main.ui.workspace.sidebar.options.path :as path]
   [uxbox.main.ui.workspace.sidebar.options.rect :as rect]
   [uxbox.main.ui.workspace.sidebar.options.text :as text]
   [uxbox.util.dom :as dom]
   [uxbox.util.http :as http]
   [uxbox.util.i18n :as i18n :refer [tr t]]
   [uxbox.util.object :as obj]))

;; --- Options

(defn- request-screenshot
  [page-id shape-id]
  (http/send! {:method :get
               :uri "/export/bitmap"
               :query {:page-id page-id
                       :object-id shape-id}}
              {:credentials? true
               :response-type :blob}))

(defn- trigger-download
  [name blob]
  (let [link (dom/create-element "a")
        uri  (dom/create-uri blob)]
    (obj/set! link "href" uri)
    (obj/set! link "download" (str/slug name))
    (obj/set! (.-style ^js link) "display" "none")
    (.appendChild (.-body ^js js/document) link)
    (.click link)
    (.remove link)))

(mf/defc shape-export
  {::mf/wrap [mf/memo]}
  [{:keys [shape page] :as props}]
  (let [loading? (mf/use-state false)
        locale   (mf/deref i18n/locale)
        on-click (fn [event]
                   (dom/prevent-default event)
                   (swap! loading? not)
                   (->> (request-screenshot (:id page) (:id shape))
                        (rx/subs
                         (fn [{:keys [status body] :as response}]
                           (if (= status 200)
                             (trigger-download (:name shape) body)
                             (st/emit! (dm/error (tr "errors.unexpected-error")))))
                         (constantly nil)
                         (fn []
                           (swap! loading? not)))))]

    [:div.element-set
     [:div.btn-large.btn-icon-dark
      {:on-click (when-not @loading? on-click)
       :class (dom/classnames
               :btn-disabled @loading?)
       :disabled @loading?}
      (if @loading?
        (t locale "workspace.options.exporting-object")
        (t locale "workspace.options.export-object"))]]))

(mf/defc shape-options
  {::mf/wrap [#(mf/throttle % 60)]}
  [{:keys [shape page] :as props}]
  [:*
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
     nil)
   [:& shape-export {:shape shape :page page}]])


(mf/defc options-content
  {::mf/wrap [mf/memo]}
  [{:keys [section selected shape page] :as props}]
  (let [locale (mf/deref i18n/locale)]
    [:div.tool-window
     [:div.tool-window-content
      [:& tab-container {:on-change-tab #(st/emit! (udw/set-options-mode %))
                         :selected section}
       [:& tab-element {:id :design
                        :title (t locale "workspace.options.design")}
        [:div.element-options
         [:& align-options]
         (if (= (count selected) 1)
           [:& shape-options {:shape shape :page page}]
           [:& page/options {:page page}])]]

       [:& tab-element {:id :prototype
                        :title (t locale "workspace.options.prototype")}
        [:div.element-options
         [:& interactions-menu {:shape shape}]]]]]]))


(mf/defc options-toolbox
  {::mf/wrap [mf/memo]}
  [{:keys [page local] :as props}]
  (let [selected   (:selected local)
        section    (:options-mode local)
        shape-id   (first selected)
        page-id    (:id page)
        shape-iref (-> (mf/deps shape-id page-id)
                       (mf/use-memo #(refs/object-by-id shape-id)))
        shape      (mf/deref shape-iref)]
    [:& options-content {:selected selected
                         :shape shape
                         :page page
                         :section section}]))


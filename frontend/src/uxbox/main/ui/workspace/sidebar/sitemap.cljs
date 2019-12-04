;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.sitemap
  (:require
   [cuerdas.core :as str]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.pages :as udp]
   [uxbox.main.data.projects :as dp]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.store :as st]
   [uxbox.main.refs :as refs]
   [uxbox.main.ui.confirm :refer [confirm-dialog]]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.workspace.sidebar.sitemap-forms :refer [page-form-dialog]]
   [uxbox.main.ui.workspace.sortable :refer [use-sortable]]
   [uxbox.util.data :refer [classnames enumerate]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.router :as rt]))

;; --- Page Item

(mf/defc page-item
  [{:keys [page index deletable? selected?] :as props}]
  (letfn [(on-edit [event]
            (modal/show! page-form-dialog {:page page}))
          (delete []
            (st/emit! (dw/delete-page (:id page))))
          (on-delete [event]
            (dom/prevent-default event)
            (dom/stop-propagation event)
            (modal/show! confirm-dialog {:on-accept delete}))
          (on-drop [item monitor]
            (prn "TODO"))
          (on-hover [item monitor]
            (st/emit! (dw/change-page-order {:id (:id item)
                                             :index index})))]
    (let [[dprops ref] (use-sortable {:type "page-item"
                                      :data {:id (:id page)
                                             :index index}
                                      :on-hover on-hover
                                      :on-drop on-drop})]
      [:li {:ref ref :class (classnames :selected selected?)}
       [:div.element-list-body
        {:class (classnames :selected selected?
                            :dragging (:dragging? dprops))
         :on-click #(st/emit! (rt/nav :workspace/page {:project (:project-id page)
                                                       :page (:id page)}))
         :on-double-click #(dom/stop-propagation %)
         :draggable true}

        [:div.page-icon i/page]
        [:span (:name page)]
        [:div.page-actions {}
         [:a {:on-click on-edit} i/pencil]
         (when deletable?
           [:a {:on-click on-delete} i/trash])]]])))


;; --- Page Item Wrapper

(defn- make-page-ref
  [page-id]
  (-> (l/in [:pages page-id])
      (l/derive st/state)))

(mf/defc page-item-wrapper
  [{:keys [page-id index deletable? selected?] :as props}]
  (let [page-ref (mf/use-memo {:deps #js [page-id]
                               :fn #(make-page-ref page-id)})
        page (mf/deref page-ref)]
    [:& page-item {:page page
                   :index index
                   :deletable? deletable?
                   :selected? selected?}]))

;; --- Pages List

(mf/defc pages-list
  [{:keys [project current-page-id] :as props}]
  (let [pages (enumerate (:pages project))
        deletable? (> (count pages) 1)]
    [:ul.element-list
     (for [[index page-id] pages]
       [:& page-item-wrapper
        {:page-id page-id
         :index index
         :deletable? deletable?
         :selected? (= page-id current-page-id)
         :key page-id}])]))

;; --- Sitemap Toolbox

(def ^:private workspace-project
  (letfn [(selector [state]
            (let [project-id (get-in state [:workspace-page :project-id])]
              (get-in state [:projects project-id])))]
    (-> (l/lens selector)
        (l/derive st/state))))

(mf/defc sitemap-toolbox
  [{:keys [project-id current-page-id] :as props}]
  (let [project (mf/deref workspace-project)
        create #(modal/show! page-form-dialog {:page {:project-id project-id}})
        close #(st/emit! (dw/toggle-flag :sitemap))]
    [:div.sitemap.tool-window
     [:div.tool-window-bar
      [:div.tool-window-icon i/project-tree]
      [:span (tr "ds.settings.sitemap")]
      [:div.tool-window-close {:on-click close} i/close]]
     [:div.tool-window-content
      [:div.project-title
       [:span (:name project)]
       [:div.add-page {:on-click create} i/close]]
      [:& pages-list {:project project
                      :current-page-id current-page-id}]]]))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
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
   [uxbox.main.ui.confirm :refer [confirm-dialog]]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.workspace.sidebar.sitemap-forms :refer [page-form-dialog]]
   [uxbox.main.ui.workspace.sortable :refer [use-sortable]]
   [uxbox.util.data :refer [classnames]]
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
            (st/emit! (udp/rehash-pages (:project-id page))))
          (on-hover [item monitor]
            (st/emit! (udp/move-page {:project-id (:project-id item)
                                      :page-id (:page-id item)
                                      :index index})))]
    (let [[dprops ref] (use-sortable {:type "page-item"
                                      :data {:page-id (:id page)
                                             :project-id (:project-id page)
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

;; --- Pages List

(defn- make-pages-iref
  [{:keys [id pages] :as project}]
  (-> (l/lens (fn [s] (into [] (map #(get-in s [:pages %])) pages)))
      (l/derive st/state {:equals? =})))

(def ^:private pages-map-iref
  (-> (l/key :pages)
      (l/derive st/state)))

(mf/defc pages-list
  [{:keys [project current-page-id] :as props}]
  (let [pages-map (mf/deref pages-map-iref)
        pages (map #(get pages-map %) (:pages project))
        deletable? (> (count pages) 1)]
    [:ul.element-list
     (for [[index item] (map-indexed vector pages)]
       [:& page-item {:page item
                      :index index
                      :deletable? deletable?
                      :selected? (= (:id item) current-page-id)
                      :key (:id item)}])]))

;; --- Sitemap Toolbox

(mf/defc sitemap-toolbox
  [{:keys [project-id current-page-id] :as props}]
  (let [project-iref (mf/use-memo {:deps #js [project-id]
                                   :fn #(-> (l/in [:projects project-id])
                                            (l/derive st/state))})
        project (mf/deref project-iref)
        create #(modal/show! page-form-dialog {:page {:project project-id}})
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

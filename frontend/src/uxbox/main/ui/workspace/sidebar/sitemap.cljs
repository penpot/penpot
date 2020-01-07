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
  (let [on-edit #(modal/show! page-form-dialog {:page page})
        delete-fn #(st/emit! (dp/delete-page (:id page)))
        on-delete #(do
                     (dom/prevent-default %)
                     (dom/stop-propagation %)
                     (modal/show! confirm-dialog {:on-accept delete-fn}))
        on-drop #(do (prn "TODO"))
        on-hover #(st/emit! (dw/change-page-order {:id (:id page)
                                                   :index index}))

        navigate-fn #(st/emit! (dw/go-to-page (:id page)))
        [dprops ref] (use-sortable {:type "page-item"
                                    :data {:id (:id page)
                                           :index index}
                                    :on-hover on-hover
                                    :on-drop on-drop})]
    [:li {:ref ref :class (classnames :selected selected?)}
     [:div.element-list-body
      {:class (classnames :selected selected?
                          :dragging (:dragging? dprops))
       :on-click navigate-fn
       :on-double-click #(dom/stop-propagation %)
       :draggable true}

      [:div.page-icon i/page]
      [:span (:name page)]
      [:div.page-actions {}
       [:a {:on-click on-edit} i/pencil]
       (when deletable?
         [:a {:on-click on-delete} i/trash])]]]))


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
  [{:keys [file current-page] :as props}]
  (let [pages (enumerate (:pages file))
        deletable? (> (count pages) 1)]
    [:ul.element-list
     (for [[index page-id] pages]
       [:& page-item-wrapper
        {:page-id page-id
         :index index
         :deletable? deletable?
         :selected? (= page-id (:id current-page))
         :key page-id}])]))

;; --- Sitemap Toolbox

(mf/defc sitemap-toolbox
  [{:keys [file page] :as props}]
  (let [create-fn #(modal/show! page-form-dialog {:page {:file-id (:file-id page)}})
        close-fn  #(st/emit! (dw/toggle-layout-flag :sitemap))]
    [:div.sitemap.tool-window
     [:div.tool-window-bar
      [:span (tr "ds.settings.sitemap")]
      [:div.add-page {:on-click create-fn} i/close]]
     [:div.tool-window-content
      [:& pages-list {:file file :current-page page}]]]))

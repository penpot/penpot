;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.sitemap
  (:require
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.alpha :as mf]
   [uxbox.common.data :as d]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.store :as st]
   [uxbox.main.refs :as refs]
   [uxbox.main.ui.confirm :refer [confirm-dialog]]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.hooks :as hooks]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :as i18n :refer [t]]
   [uxbox.util.router :as rt]))

;; --- Page Item

(mf/defc page-item
  [{:keys [page index deletable? selected?] :as props}]
  (let [local (mf/use-state {})

        on-double-click
        (fn [event]
          (dom/prevent-default event)
          (dom/stop-propagation event)
          (swap! local assoc :edition true))

        on-blur
        (fn [event]
          (let [target (dom/event->target event)
                name (dom/get-value target)]
            (st/emit! (dw/rename-page (:id page) name))
            (swap! local assoc :edition false)))

        on-key-down (fn [event]
                      (cond
                        (kbd/enter? event)
                        (on-blur event)

                        (kbd/esc? event)
                        (swap! local assoc :edition false)))

        delete-fn #(st/emit! (dw/delete-page (:id page)))
        on-delete #(do
                     (dom/prevent-default %)
                     (dom/stop-propagation %)
                     (modal/show! confirm-dialog {:on-accept delete-fn}))

        navigate-fn #(st/emit! (dw/go-to-page (:id page)))

        on-drop
        (fn [side {:keys [id name] :as data}]
          (let [index (if (= :bot side) (inc index) index)]
            (st/emit! (dw/relocate-page id index))))

        [dprops dref] (hooks/use-sortable
                       :type "page"
                       :on-drop on-drop
                       :data {:id (:id page)
                              :index index
                              :name (:name page)})]

    [:li {:class (dom/classnames
                  :selected selected?
                  :dnd-over-top (= (:over dprops) :top)
                  :dnd-over-bot (= (:over dprops) :bot))
          :ref dref}
     [:div.element-list-body {:class (dom/classnames
                                      :selected selected?)
                              :on-click navigate-fn
                              :on-double-click on-double-click}
      [:div.page-icon i/file-html]
      (if (:edition @local)
        [:*
         [:input.element-name {:type "text"
                               :on-blur on-blur
                               :on-key-down on-key-down
                               :auto-focus true
                               :default-value (:name page "")}]]
        [:*
         [:span (:name page)]
         [:div.page-actions
          (when deletable?
            [:a {:on-click on-delete} i/trash])]])]]))


;; --- Page Item Wrapper

(defn- make-page-iref
  [id]
  #(l/derived (fn [state]
                (let [page (get-in state [:workspace-pages id])]
                  (select-keys page [:id :name :ordering])))
              st/state =))

(mf/defc page-item-wrapper
  [{:keys [page-id index deletable? selected?] :as props}]
  (let [page-iref (mf/use-memo (mf/deps page-id)
                               (make-page-iref page-id))
        page (mf/deref page-iref)]
    [:& page-item {:page page
                   :index index
                   :deletable? deletable?
                   :selected? selected?}]))

;; --- Pages List

(mf/defc pages-list
  [{:keys [file current-page] :as props}]
  (let [pages (d/enumerate (:pages file))
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
  [{:keys [file page layout] :as props}]
  (let [on-create-click #(st/emit! dw/create-empty-page)
        toggle-layout #(st/emit! (dw/toggle-layout-flag %))
        locale (i18n/use-locale)]
    [:div.sitemap.tool-window
     [:div.tool-window-bar
      [:span (t locale "workspace.sidebar.sitemap")]
      [:div.add-page {:on-click on-create-click} i/close]
      [:div.collapse-pages {:on-click #(st/emit! (dw/toggle-layout-flag :sitemap-pages))}
       i/arrow-slide]]

     (when (contains? layout :sitemap-pages)
       [:div.tool-window-content
        [:& pages-list {:file file :current-page page}]])]))

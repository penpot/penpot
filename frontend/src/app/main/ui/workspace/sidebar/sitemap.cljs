;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.sitemap
  (:require
   [app.common.data :as d]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as i]
   [app.main.ui.keyboard :as kbd]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t]]
   [app.util.router :as rt]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

;; --- Page Item

(mf/defc page-item
  [{:keys [page index deletable? selected?] :as props}]
  (let [local       (mf/use-state {})
        input-ref   (mf/use-ref)
        id          (:id page)

        delete-fn   (mf/use-callback (mf/deps id) #(st/emit! (dw/delete-page id)))
        on-delete   (mf/use-callback (mf/deps id) #(modal/show! :confirm-dialog {:on-accept delete-fn}))
        navigate-fn (mf/use-callback (mf/deps id) #(st/emit! (dw/go-to-page id)))

        on-double-click
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (swap! local assoc :edition true)))

        on-blur
        (mf/use-callback
         (fn [event]
           (let [target (dom/event->target event)
                 name   (dom/get-value target)]
             (st/emit! (dw/rename-page id name))
             (swap! local assoc :edition false))))

        on-key-down
        (mf/use-callback
        (fn [event]
          (cond
            (kbd/enter? event)
            (on-blur event)

            (kbd/esc? event)
            (swap! local assoc :edition false))))

        on-drop
        (mf/use-callback
         (mf/deps id)
         (fn [side {:keys [id name] :as data}]
           (let [index (if (= :bot side) (inc index) index)]
             (st/emit! (dw/relocate-page id index)))))

        [dprops dref]
        (hooks/use-sortable
         :data-type "app/page"
         :on-drop on-drop
         :data {:id id
                :index index
                :name (:name page)})]

    (mf/use-layout-effect
     (mf/deps (:edition @local))
     (fn []
       (when (:edition @local)
         (let [edit-input (mf/ref-val input-ref)]
           (dom/select-text! edit-input))
         nil)))

    [:li {:class (dom/classnames
                  :selected selected?
                  :dnd-over-top (= (:over dprops) :top)
                  :dnd-over-bot (= (:over dprops) :bot))
          :ref dref}
     [:div.element-list-body
      {:class (dom/classnames
               :selected selected?)
       :on-click navigate-fn
       :on-double-click on-double-click}
      [:div.page-icon i/file-html]
      (if (:edition @local)
        [:*
         [:input.element-name {:type "text"
                               :ref input-ref
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

(defn- make-page-ref
  [page-id]
  (l/derived (fn [state]
               (let [page (get-in state [:workspace-file :data :pages-index page-id])]
                 (select-keys page [:id :name])))
              st/state =))

(mf/defc page-item-wrapper
  [{:keys [file-id page-id index deletable? selected?] :as props}]
  (let [page-ref (mf/use-memo (mf/deps page-id) #(make-page-ref page-id))
        page     (mf/deref page-ref)]
    [:& page-item {:page page
                   :index index
                   :deletable? deletable?
                   :selected? selected?}]))

;; --- Pages List

(mf/defc pages-list
  [{:keys [file] :as props}]
  (let [pages           (:pages file)
        deletable?      (> (count pages) 1)
        current-page-id (mf/use-ctx ctx/current-page-id)]
    [:ul.element-list
     [:& hooks/sortable-container {}
      (for [[index page-id] (d/enumerate pages)]
        [:& page-item-wrapper
         {:page-id page-id
          :index index
          :deletable? deletable?
          :selected? (= page-id current-page-id)
          :key page-id}])]]))

;; --- Sitemap Toolbox

(mf/defc sitemap
  [{:keys [layout] :as props}]
  (let [create      (mf/use-callback #(st/emit! dw/create-empty-page))
        locale      (mf/deref i18n/locale)
        show-pages? (mf/use-state true)

        file        (mf/deref refs/workspace-file)

        toggle-pages
        (mf/use-callback #(reset! show-pages? not))]

    [:div.sitemap.tool-window
     [:div.tool-window-bar
      [:span (t locale "workspace.sidebar.sitemap")]
      [:div.add-page {:on-click create} i/close]
      [:div.collapse-pages {:on-click toggle-pages} i/arrow-slide]]

     (when @show-pages?
       [:div.tool-window-content
        [:& pages-list {:file file :key (:id file)}]])]))

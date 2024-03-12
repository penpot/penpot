;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.sitemap
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as i]
   [app.main.ui.notifications.badge :refer [badge-notification]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; --- Page Item

(mf/defc page-item
  {::mf/wrap-props false}
  [{:keys [page index deletable? selected? editing? hovering?]}]
  (let [input-ref            (mf/use-ref)
        id                   (:id page)
        delete-fn            (mf/use-fn (mf/deps id) #(st/emit! (dw/delete-page id)))
        navigate-fn          (mf/use-fn (mf/deps id) #(st/emit! :interrupt (dw/go-to-page id)))
        workspace-read-only? (mf/use-ctx ctx/workspace-read-only?)

        on-delete
        (mf/use-fn
         (mf/deps id)
         #(st/emit! (modal/show
                     {:type :confirm
                      :title (tr "modals.delete-page.title")
                      :message (tr "modals.delete-page.body")
                      :on-accept delete-fn})))

        on-double-click
        (mf/use-fn
         (mf/deps workspace-read-only?)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (when-not workspace-read-only?
             (st/emit! (dw/start-rename-page-item id)))))

        on-blur
        (mf/use-fn
         (fn [event]
           (let [name   (str/trim (dom/get-target-val event))]
             (when-not (str/empty? name)
               (st/emit! (dw/rename-page id name)))
             (st/emit! (dw/stop-rename-page-item)))))

        on-key-down
        (mf/use-fn
         (fn [event]
           (cond
             (kbd/enter? event)
             (on-blur event)

             (kbd/esc? event)
             (st/emit! (dw/stop-rename-page-item)))))

        on-drop
        (mf/use-fn
         (mf/deps id index)
         (fn [side {:keys [id] :as data}]
           (let [index (if (= :bot side) (inc index) index)]
             (st/emit! (dw/relocate-page id index)))))

        [dprops dref]
        (hooks/use-sortable
         :data-type "penpot/page"
         :on-drop on-drop
         :data {:id id
                :index index
                :name (:name page)}
         :draggable? (not workspace-read-only?))

        on-context-menu
        (mf/use-fn
         (mf/deps id workspace-read-only?)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (when-not workspace-read-only?
             (let [position (dom/get-client-position event)]
               (st/emit! (dw/show-page-item-context-menu
                          {:position position
                           :page page
                           :deletable? deletable?}))))))]

    (mf/use-effect
     (mf/deps selected?)
     (fn []
       (when selected?
         (let [node (mf/ref-val dref)]
           (dom/scroll-into-view-if-needed! node)))))

    (mf/use-layout-effect
     (mf/deps editing?)
     (fn []
       (when editing?
         (let [edit-input (mf/ref-val input-ref)]
           (dom/select-text! edit-input))
         nil)))

    [:li {:class (stl/css-case
                  :page-element true
                  :selected selected?
                  :dnd-over-top (= (:over dprops) :top)
                  :dnd-over-bot (= (:over dprops) :bot))
          :ref dref}
     [:div {:class (stl/css-case
                    :element-list-body true
                    :hover hovering?
                    :selected selected?)
            :data-test (dm/str "page-" id)
            :tab-index "0"
            :on-click navigate-fn
            :on-double-click on-double-click
            :on-context-menu on-context-menu}
      [:div {:class (stl/css :page-icon)}
       i/document]

      (if editing?
        [:*
         [:input {:class  (stl/css :element-name)
                  :type "text"
                  :ref input-ref
                  :on-blur on-blur
                  :on-key-down on-key-down
                  :auto-focus true
                  :default-value (:name page "")}]]
        [:*
         [:span {:class (stl/css :page-name)}
          (:name page)]
         [:div {:class  (stl/css :page-actions)}
          (when (and deletable? (not workspace-read-only?))
            [:button {:on-click on-delete}
             i/delete])]])]]))

;; --- Page Item Wrapper

(defn- make-page-ref
  [page-id]
  (l/derived (fn [state]
               (let [page (get-in state [:workspace-data :pages-index page-id])]
                 (select-keys page [:id :name])))
             st/state =))

(mf/defc page-item-wrapper
  {::mf/wrap-props false}
  [{:keys [page-id index deletable? selected? editing?]}]
  (let [page-ref (mf/use-memo (mf/deps page-id) #(make-page-ref page-id))
        page     (mf/deref page-ref)]
    [:& page-item {:page page
                   :index index
                   :deletable? deletable?
                   :selected? selected?
                   :editing? editing?}]))

;; --- Pages List

(mf/defc pages-list
  {::mf/wrap-props false}
  [{:keys [file]}]
  (let [pages           (:pages file)
        deletable?      (> (count pages) 1)
        editing-page-id (mf/deref refs/editing-page-item)
        current-page-id (mf/use-ctx ctx/current-page-id)]
    [:ul {:class (stl/css :page-list)}
     [:& hooks/sortable-container {}
      (for [[index page-id] (d/enumerate pages)]
        [:& page-item-wrapper
         {:page-id page-id
          :index index
          :deletable? deletable?
          :editing? (= page-id editing-page-id)
          :selected? (= page-id current-page-id)
          :key page-id}])]]))

;; --- Sitemap Toolbox

(mf/defc sitemap
  {::mf/wrap-props false}
  [{:keys [size show-pages? toggle-pages]}]
  (let [file           (mf/deref refs/workspace-file)
        file-id        (get file :id)
        project-id     (get file :project-id)

        on-create      (mf/use-fn
                        (mf/deps file-id project-id)
                        (fn [event]
                          (st/emit! (dw/create-page {:file-id file-id :project-id project-id}))
                          (-> event dom/get-current-target dom/blur!)))
        size           (if show-pages? size 32)
        read-only?     (mf/use-ctx ctx/workspace-read-only?)]

    [:div {:class (stl/css :sitemap)
           :style #js {"--height" (str size "px")}}

     [:& title-bar {:collapsable   true
                    :collapsed     (not show-pages?)
                    :on-collapsed  toggle-pages
                    :all-clickable true
                    :title         (tr "workspace.sidebar.sitemap")
                    :class         (stl/css :title-spacing-sitemap)}

      (if ^boolean read-only?
        [:& badge-notification {:is-focus true
                                :size :small
                                :content (tr "labels.view-only")}]
        [:button {:class (stl/css :add-page)
                  :on-click on-create}
         i/add])]

     [:div {:class (stl/css :tool-window-content)}
      [:& pages-list {:file file :key (:id file)}]]]))

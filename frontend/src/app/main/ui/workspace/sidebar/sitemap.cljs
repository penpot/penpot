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
   [app.main.data.common :as dcm]
   [app.main.data.helpers :as dsh]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.notifications.badge :refer [badge-notification]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; FIXME: can we unify this two refs in one?

(def ^:private ref:file-with-pages
  "A derived state of the current file, without data with the
  exception of list of pages"
  (l/derived (fn [{:keys [data] :as file}]
               (-> file
                   (dissoc :data)
                   (assoc :pages (:pages data))))
             refs/file
             =))

(defn- make-page-ref
  "Create a derived state that poins to a page identified by `page-id`
  without including the page objects (mainly for avoid rerender on
  each object change)"
  [page-id]
  (l/derived (fn [fdata]
               (-> (dsh/get-page fdata page-id)
                   (dissoc :objects)))
             refs/workspace-data
             =))

;; --- Page Item

(mf/defc page-item
  {::mf/wrap-props false}
  [{:keys [page index deletable? selected? editing? hovering?]}]
  (let [input-ref    (mf/use-ref)
        id           (:id page)
        delete-fn    (mf/use-fn (mf/deps id) #(st/emit! (dw/delete-page id)))
        navigate-fn  (mf/use-fn (mf/deps id) #(st/emit! :interrupt (dcm/go-to-workspace :page-id id)))
        read-only?   (mf/use-ctx ctx/workspace-read-only?)

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
         (mf/deps read-only?)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (when-not read-only?
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
         :draggable? (and (not read-only?) (not editing?)))

        on-context-menu
        (mf/use-fn
         (mf/deps id read-only?)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (when-not read-only?
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
            :data-testid (dm/str "page-" id)
            :tab-index "0"
            :on-click navigate-fn
            :on-double-click on-double-click
            :on-context-menu on-context-menu}
      [:div {:class (stl/css :page-icon)}
       deprecated-icon/document]

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
         [:span {:class (stl/css :page-name) :title (:name page) :data-testid "page-name"}
          (:name page)]
         [:div {:class  (stl/css :page-actions)}
          (when (and deletable? (not read-only?))
            [:button {:on-click on-delete}
             deprecated-icon/delete])]])]]))

;; --- Page Item Wrapper

(mf/defc page-item-wrapper
  {::mf/wrap-props false}
  [{:keys [page-id index deletable? selected? editing?]}]
  (let [page-ref (mf/with-memo [page-id]
                   (make-page-ref page-id))
        page     (mf/deref page-ref)]
    [:& page-item {:page page
                   :index index
                   :deletable? deletable?
                   :selected? selected?
                   :editing? editing?}]))

;; --- Pages List

(mf/defc pages-list*
  {::mf/private true}
  [{:keys [file]}]
  (let [pages           (:pages file)
        deletable?      (> (count pages) 1)
        editing-page-id (mf/deref refs/editing-page-item)
        current-page-id (mf/use-ctx ctx/current-page-id)]
    [:ul {:class (stl/css :page-list)}
     [:> hooks/sortable-container* {}
      (for [[index page-id] (d/enumerate pages)]
        [:& page-item-wrapper
         {:page-id page-id
          :index index
          :deletable? deletable?
          :editing? (= page-id editing-page-id)
          :selected? (= page-id current-page-id)
          :key page-id}])]]))

;; --- Sitemap Toolbox

(mf/defc sitemap*
  [{:keys [height collapsed on-toggle-collapsed]}]
  (let [file           (mf/deref ref:file-with-pages)
        file-id        (get file :id)
        project-id     (get file :project-id)

        on-create      (mf/use-fn
                        (mf/deps file-id project-id)
                        (fn [event]
                          (st/emit! (dw/create-page {:file-id file-id :project-id project-id}))
                          (-> event dom/get-current-target dom/blur!)))

        read-only?     (mf/use-ctx ctx/workspace-read-only?)
        permissions    (mf/use-ctx ctx/permissions)]

    [:div {:class (stl/css :sitemap)
           :style {:--height (dm/str height "px")}}

     [:> title-bar* {:collapsable   true
                     :collapsed     collapsed
                     :on-collapsed  on-toggle-collapsed
                     :all-clickable true
                     :title         (tr "workspace.sidebar.sitemap")
                     :class         (stl/css :title-spacing-sitemap)}

      (if ^boolean read-only?
        (when ^boolean (:can-edit permissions)
          [:& badge-notification {:is-focus true
                                  :size :small
                                  :content (tr "labels.view-only")}])
        [:> icon-button* {:variant "ghost"
                          :class (stl/css :add-page)
                          :aria-label (tr "workspace.sidebar.sitemap.add-page")
                          :on-click on-create
                          :icon i/add}])]

     (when-not ^boolean collapsed
       [:div {:class (stl/css :tool-window-content)}
        [:> pages-list* {:file file :key (dm/str (:id file))}]])]))


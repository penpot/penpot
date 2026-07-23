;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.sitemap
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.page :as ctp]
   [app.main.data.common :as dcm]
   [app.main.data.helpers :as dsh]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.notifications.badge :refer [badge-notification]]
   [app.render-wasm.api :as wasm.api]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.timers :as timers]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [promesa.core :as p]
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
               (let [page (dsh/get-page fdata page-id)]
                 (-> page
                     (assoc :empty? (ctp/is-empty? page))
                     (dissoc :objects))))
             refs/workspace-data
             =))



;; --- Page Item

(mf/defc page-item*
  {::mf/private true
   ::mf/wrap-props false}
  [{:keys [page index is-deletable is-selected is-editing is-hovering current-page-id]}]
  (let [input-ref     (mf/use-ref)
        id            (:id page)
        name          (:name page "")
        is-separator? (and (= "---" (str/trim name)) (:empty? page))
        delete-fn     (mf/use-fn (mf/deps id) #(st/emit! (dw/delete-page id)))
        navigate-fn   (mf/use-fn (mf/deps id) #(st/emit! :interrupt (dcm/go-to-workspace :page-id id)))
        read-only?    (mf/use-ctx ctx/workspace-read-only?)

        on-click
        (mf/use-fn
         (mf/deps id current-page-id is-separator?)
         (fn [event]
           (when-not is-separator?
             (cond
               ;; Shift + click: select the range of pages from the anchor
               ;; to this page (does not navigate).
               (kbd/shift? event)
               (st/emit! (dw/select-pages-range id))

               ;; Ctrl/Cmd + click: add/remove this page to/from the
               ;; multi-selection (does not navigate).
               (kbd/mod? event)
               (st/emit! (dw/toggle-page-selection id))

               ;; Plain click: reset the selection to this page and
               ;; navigate to it.
               :else
               (do
                 (st/emit! (dw/select-page id))
                 ;; WASM page transitions:
                 ;; - Capture the current page (A) once
                 ;; - Show a blurred snapshot while the target page (B/C/...) renders
                 ;; - If the user clicks again during the transition, keep showing the original (A) snapshot
                 (if (and (features/active-feature? @st/state "render-wasm/v1")
                          (not= id current-page-id))
                   (-> (if @wasm.api/page-transition?
                         (p/resolved nil)
                         ;; Blur with Skia, then capture the already-blurred frame.
                         (do (wasm.api/render-blurred-snapshot!)
                             (wasm.api/capture-canvas-snapshot)))
                       (p/finally
                         (fn []
                           (wasm.api/apply-canvas-blur)
                           ;; Two RAF so the overlay paints before navigation.
                           (timers/raf
                            (fn []
                              (timers/raf navigate-fn))))))
                   (navigate-fn)))))))

        on-delete
        (mf/use-fn
         (mf/deps id)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (modal/show {:type :confirm
                                  :title (tr "modals.delete-page.title")
                                  :message (tr "modals.delete-page.body")
                                  :on-accept delete-fn}))))

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
         (mf/deps id is-separator?)
         (fn [event]
           (let [new-name (str/trim (dom/get-target-val event))]
             (if (str/empty? new-name)
               (when is-separator?
                 (st/emit! (dw/delete-page id)))
               (st/emit! (dw/rename-page id new-name))))
           (st/emit! (dw/stop-rename-page-item))))

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
         :draggable? (and (not read-only?) (not is-editing)))

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
                           :deletable? is-deletable}))))))]

    (mf/use-effect
     (mf/deps is-selected)
     (fn []
       (when is-selected
         (let [node (mf/ref-val dref)]
           (dom/scroll-into-view-if-needed! node)))))

    (mf/use-layout-effect
     (mf/deps is-editing)
     (fn []
       (when is-editing
         (let [edit-input (mf/ref-val input-ref)]
           (dom/select-text! edit-input))
         nil)))

    (let [selected? (and is-selected (not is-separator?))]
      [:li {:class (stl/css-case :page-item true
                                 :separator is-separator?
                                 :selected selected?
                                 :dnd-over-top (= (:over dprops) :top)
                                 :dnd-over-bot (= (:over dprops) :bot))
            :ref dref}
       [:div {:class (stl/css-case :page-item-body true
                                   :separator is-separator?
                                   :hover (and is-hovering (not is-separator?))
                                   :selected selected?)
              :data-testid (dm/str "page-" id)
              :tab-index "0"
              :on-click on-click
              :on-double-click on-double-click
              :on-context-menu on-context-menu}
        (if (and is-separator? (not is-editing))
          [:div {:class (stl/css :page-divider)
                 :data-testid "page-separator"}]
          [:*
           (when-not is-separator?
             [:div {:class (stl/css :page-item-icon)}
              [:> icon* {:icon-id i/document
                         :size "s"}]])
           (if is-editing
             [:input {:class        (stl/css :page-item-input)
                      :type         "text"
                      :ref          input-ref
                      :on-blur      on-blur
                      :on-key-down  on-key-down
                      :auto-focus   true
                      :default-value name}]
             [:*
              [:span {:class (stl/css :page-item-label)
                      :title name
                      :data-testid "page-name"}
               name]
              [:div {:class (stl/css :page-item-actions)}
               (when (and is-deletable (not read-only?))
                 [:> icon-button* {:variant "action"
                                   :aria-label (tr "modals.delete-page.title")
                                   :on-click on-delete
                                   :icon-size "s"
                                   :class (stl/css :page-delete-btn)
                                   :icon-class (stl/css :page-delete-icon)
                                   :icon i/delete}])]])])]])))

;; --- Page Item Wrapper

(mf/defc page-item-wrapper*
  {::mf/private true
   ::mf/wrap-props false}
  [{:keys [page-id index is-deletable is-selected is-editing current-page-id]}]
  (let [page-ref (mf/with-memo [page-id]
                   (make-page-ref page-id))
        page     (mf/deref page-ref)]
    [:> page-item* {:page page
                    :index index
                    :current-page-id current-page-id
                    :is-deletable is-deletable
                    :is-selected is-selected
                    :is-editing is-editing}]))

;; --- Pages List

(mf/defc pages-list*
  {::mf/private true}
  [{:keys [file]}]
  (let [pages           (:pages file)
        deletable?      (> (count pages) 1)
        editing-page-id (mf/deref refs/editing-page-item)
        selected-pages  (mf/deref refs/selected-pages)
        current-page-id (mf/use-ctx ctx/current-page-id)
        ;; When there is no explicit multi-selection, the current page
        ;; is the selected one.
        selected-pages  (if (seq selected-pages)
                          selected-pages
                          #{current-page-id})]
    [:ul
     [:> hooks/sortable-container* {}
      (for [[index page-id] (d/enumerate pages)]
        [:> page-item-wrapper* {:page-id page-id
                                :index index
                                :is-deletable deletable?
                                :is-editing (= page-id editing-page-id)
                                :is-selected (contains? selected-pages page-id)
                                :current-page-id current-page-id
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
                          (st/emit! (dw/create-page {:file-id file-id
                                                     :project-id project-id}))
                          (-> event dom/get-current-target dom/blur!)))

        read-only?     (mf/use-ctx ctx/workspace-read-only?)
        permissions    (mf/use-ctx ctx/permissions)]

    [:div {:class (stl/css :sitemap)
           :style {:--height (dm/str "calc(" height "px * var(--ui-scale))")}}

     [:> title-bar* {:collapsable   true
                     :collapsed     collapsed
                     :on-collapsed  on-toggle-collapsed
                     :title         (tr "workspace.sidebar.sitemap")
                     :class         (stl/css :sitemap-title)}

      (if ^boolean read-only?
        (when ^boolean (:can-edit permissions)
          [:& badge-notification {:is-focus true
                                  :size :small
                                  :content (tr "labels.view-only")}])
        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "workspace.sidebar.sitemap.add-page")
                          :on-click on-create
                          :icon i/add}])]

     (when-not ^boolean collapsed
       [:div {:class (stl/css :sitemap-content)}
        [:> pages-list* {:key (dm/str (:id file))
                         :file file}]])]))

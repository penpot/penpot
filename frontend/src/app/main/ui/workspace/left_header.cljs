;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.left-header
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.colors :as dc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.main-menu :as main-menu]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.router :as rt]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; --- Header Component

(mf/defc left-header
  {::mf/wrap-props false}
  [{:keys [file layout project page-id class]}]
  (let [profile     (mf/deref refs/profile)
        file-id     (:id file)
        file-name   (:name file)
        project-id  (:id project)
        team-id     (:team-id project)
        shared?     (:is-shared file)
        read-only?  (mf/use-ctx ctx/workspace-read-only?)

        editing*    (mf/use-state false)
        editing?    (deref editing*)
        input-ref   (mf/use-ref nil)

        handle-blur
        (mf/use-fn
         (mf/deps file-id)
         (fn [_]
           (let [value (str/trim (-> input-ref mf/ref-val dom/get-value))]
             (when (not= value "")
               (st/emit! (dw/rename-file file-id value)))
             (reset! editing* false))))

        handle-name-keydown
        (mf/use-fn
         (mf/deps handle-blur)
         (fn [event]
           (when (kbd/enter? event)
             (handle-blur event))))

        start-editing-name
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (reset! editing* true)))

        close-modals
        (mf/use-fn
         #(st/emit! (dc/stop-picker)
                    (modal/hide)))

        go-back
        (mf/use-fn
         (mf/deps project)
         (fn []
           (close-modals)
           (st/emit! (dw/set-options-mode :design)
                     (dw/go-to-dashboard project))))

        nav-to-project
        (mf/use-fn
         (mf/deps team-id project-id)
         #(st/emit! (rt/nav-new-window* {:rname :dashboard-files
                                         :path-params {:team-id team-id
                                                       :project-id project-id}})))]

    (mf/with-effect [editing?]
      (when ^boolean editing?
        (dom/select-text! (mf/ref-val input-ref))))
    [:header {:class (dm/str class " " (stl/css :workspace-header-left))}
     [:a {:on-click go-back
          :class (stl/css :main-icon)} i/logo-icon]
     [:div {:alt (tr "workspace.sitemap")
            :class (stl/css :project-tree)}
      [:div
       {:class (stl/css :project-name)
        :on-click nav-to-project}
       (:name project)]
      (if ^boolean editing?
        [:input
         {:class (stl/css :file-name-input)
          :type "text"
          :ref input-ref
          :on-blur handle-blur
          :on-key-down handle-name-keydown
          :auto-focus true
          :default-value (:name file "")}]
        [:div
         {:class (stl/css :file-name)
          :title file-name
          :on-double-click start-editing-name}
         file-name])]
     (when ^boolean shared?
       [:span {:class (stl/css :shared-badge)} i/library])
     [:div {:class (stl/css :menu-section)}
      [:& main-menu/menu
       {:layout layout
        :file file
        :profile profile
        :read-only? read-only?
        :team-id team-id
        :page-id page-id}]]]))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.left-header
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.config :as cf]
   [app.main.data.common :as dcm]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.colors :as dc]
   [app.main.refs :as refs]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [app.main.ui.ds.foundations.assets.raw-svg :as raw-svg :refer [raw-svg*]]
   [app.main.ui.ds.tooltip :refer [tooltip*]]
   [app.main.ui.workspace.branch-switcher :refer [branch-bar*]]
   [app.main.ui.workspace.main-menu :as main-menu]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; --- Header Component

(mf/defc left-header*
  [{:keys [file layout project class]}]
  (let [file-id     (:id file)
        file-name   (:name file)
        project-id  (:id project)
        shared?     (:is-shared file)

        ;; When the open file is a branch, `branch-ctx` is non-nil (the
        ;; main file is never a branch, so this is also how we tell them
        ;; apart). On a branch the title is main's name: the branch itself
        ;; is named by the chip in the branch row below.
        branch-ctx  (mf/deref refs/branch-context)
        branch?     (and (some? branch-ctx) (some? (:source-name branch-ctx)))

        ;; When a pull request review is active the header gets a
        ;; distinct badge and the pull request title, so the user knows a
        ;; review is open. The file is still the live, editable branch.
        pr-preview  (mf/deref refs/pull-request-preview)
        review?     (some? pr-preview)
        pr-title    (get-in pr-preview [:info :title] "")

        display-name
        (cond
          ^boolean review?
          (tr "workspace.pull-requests.header-title" pr-title)

          ^boolean branch?
          (:source-name branch-ctx)

          :else
          file-name)

        persistence
        (mf/deref refs/persistence)

        ;; Nothing queued to save means the file is up to date.
        persistence-status
        (or (:status persistence) :saved)

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

        router      (mf/deref refs/router)
        recent-href (rt/resolve-uri router :dashboard-recent {:team-id (:team-id project)})
        project-href (rt/resolve-uri router :dashboard-files {:team-id (:team-id project)
                                                              :project-id project-id})

        go-back
        (mf/use-fn
         (fn [event]
           ;; Ctrl/Cmd-click opens the href in a new tab, as any link.
           (when-not (kbd/mod? event)
             (dom/prevent-default event)
             (close-modals)
             ;; FIXME: move set-mode to uri?
             (st/emit! :interrupt
                       (dw/set-options-mode :design)
                       (dcm/go-to-dashboard-recent)))))]

    (mf/with-effect [editing?]
      (when ^boolean editing?
        (dom/select-text! (mf/ref-val input-ref))))

    [:div {:class (dm/str class " " (stl/css :workspace-header-left-wrapper))}
     [:header {:class (stl/css :workspace-header-left)}
      [:a {:href recent-href
           :on-click go-back
           :class (stl/css :main-icon)}
       [:> raw-svg* {:id raw-svg/penpot-logo-icon
                     :class (stl/css :logo)}]]
      [:div {:alt (tr "workspace.sitemap")
             :class (stl/css :project-tree)}
       ;; Opens the project files in a new tab, as it always did.
       [:a {:class (stl/css :project-name)
            :href project-href
            :target "_blank"
            :rel "noopener"}
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
           :title display-name
           ;; on a branch the title is main's name, which this file does not
           ;; own, so double-click editing is disabled.
           :on-double-click (when-not ^boolean branch? start-editing-name)}
          ;; Persistence state widget
          [:div {:class (case persistence-status
                          :pending (stl/css :status-notification :pending-status)
                          :saving (stl/css :status-notification :saving-status)
                          :saved (stl/css :status-notification :saved-status)
                          :retrying (stl/css :status-notification :retrying-status)
                          :error (stl/css :status-notification :error-status)
                          (stl/css :status-notification))
                 :title (case persistence-status
                          :pending (tr "workspace.header.saving")
                          :saving (tr "workspace.header.saving")
                          :saved (tr "workspace.header.saved")
                          :retrying (tr "workspace.header.retrying")
                          :error (tr "workspace.header.save-error")
                          nil)}]
          (when ^boolean review?
            [:> tooltip* {:content (tr "workspace.pull-requests.header-badge-tooltip" pr-title)
                          :placement "bottom"}
             [:span {:class (stl/css :review-badge)}
              [:> icon* {:icon-id i/git-pull-request-arrow :size "s"}]]])
          [:div {:class (stl/css :file-name-label)}
           (if ^boolean review?
             [:*
              [:span {:class (stl/css :review-label)}
               (tr "workspace.pull-requests.header-label")]
              " " pr-title]
             display-name)]])]
      (when ^boolean shared?
        [:span {:class (stl/css :shared-badge)}
         [:> icon* {:icon-id i/library :class (stl/css :shared-badge-icon)}]])
      [:div {:class (stl/css :menu-section)}
       [:> main-menu/menu* {:layout layout
                            :file file}]]]
     (when (contains? cf/flags :branching)
       [:> branch-bar* {:file file}])]))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.right-header
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.shortcuts :as scd]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.drawing.common :as dwc]
   [app.main.data.workspace.history :as dwh]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.context :as ctx]
   [app.main.ui.dashboard.team]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.exports.assets :refer [export-progress-widget]]
   [app.main.ui.formats :as fmt]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.presence :refer [active-sessions]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ref:persistence-status
  (l/derived :status refs/persistence))

;; --- Zoom Widget

(mf/defc zoom-widget-workspace
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [{:keys [zoom on-increase on-decrease on-zoom-reset on-zoom-fit on-zoom-selected]}]
  (let [open*           (mf/use-state false)
        open?           (deref open*)

        open-dropdown
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (reset! open* true)))

        close-dropdown
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (reset! open* false)))

        on-increase
        (mf/use-fn
         (mf/deps on-increase)
         (fn [event]
           (dom/stop-propagation event)
           (on-increase)))

        on-decrease
        (mf/use-fn
         (mf/deps on-decrease)
         (fn [event]
           (dom/stop-propagation event)
           (on-decrease)))

        zoom (fmt/format-percent zoom {:precision 0})]

    [:*
     [:div {:on-click open-dropdown
            :class (stl/css-case :zoom-widget true
                                 :selected open?)
            :title (tr "workspace.header.zoom")}
      [:span {:class (stl/css :label)} zoom]]
     [:& dropdown {:show open? :on-close close-dropdown}
      [:ul {:class (stl/css :dropdown)}
       [:li {:class (stl/css :basic-zoom-bar)}
        [:span {:class (stl/css :zoom-btns)}
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "shortcuts.decrease-zoom")
                           :on-click on-decrease
                           :icon "remove"}]
         [:p {:class (stl/css :zoom-text)} zoom]
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "shortcuts.increase-zoom")
                           :on-click on-increase
                           :icon "add"}]]
        [:button {:class (stl/css :reset-btn)
                  :on-click on-zoom-reset}
         (tr "workspace.header.reset-zoom")]]
       [:li {:class (stl/css :zoom-option)
             :on-click on-zoom-fit}
        (tr "workspace.header.zoom-fit-all")
        [:span {:class (stl/css :shortcuts)}
         (for [sc (scd/split-sc (sc/get-tooltip :fit-all))]
           [:span {:class (stl/css :shortcut-key)
                   :key (str "zoom-fit-" sc)} sc])]]
       [:li {:class (stl/css :zoom-option)
             :on-click on-zoom-selected}
        (tr "workspace.header.zoom-selected")
        [:span {:class (stl/css :shortcuts)}
         (for [sc (scd/split-sc (sc/get-tooltip :zoom-selected))]
           [:span {:class (stl/css :shortcut-key)
                   :key (str "zoom-selected-" sc)} sc])]]]]]))

;; --- Header Component

(mf/defc right-header*
  [{:keys [file layout page-id]}]
  (let [file-id           (:id file)

        threads-map       (mf/deref refs/comment-threads)

        zoom              (mf/deref refs/selected-zoom)
        read-only?        (mf/use-ctx ctx/workspace-read-only?)
        selected-drawtool (mf/deref refs/selected-drawing-tool)

        on-increase       (mf/use-fn #(st/emit! (dw/increase-zoom nil)))
        on-decrease       (mf/use-fn #(st/emit! (dw/decrease-zoom nil)))
        on-zoom-reset     (mf/use-fn #(st/emit! dw/reset-zoom))
        on-zoom-fit       (mf/use-fn #(st/emit! dw/zoom-to-fit-all))
        on-zoom-selected  (mf/use-fn #(st/emit! dw/zoom-to-selected-shape))

        editing*          (mf/use-state false)
        editing?          (deref editing*)

        input-ref         (mf/use-ref nil)

        team              (mf/deref refs/team)
        permissions       (get team :permissions)

        has-unread-comments?
        (mf/with-memo [threads-map file-id]
          (->> (vals threads-map)
               (some #(and (= (:file-id %) file-id)
                           (pos? (:count-unread-comments %))))
               (boolean)))

        display-share-button?
        (and (not (:is-default team))
             (or (:is-admin permissions)
                 (:is-owner permissions)))

        nav-to-viewer
        (mf/use-fn
         (mf/deps file-id page-id)
         (fn []
           (let [params {:page-id page-id
                         :file-id file-id
                         :section "interactions"}]
             (st/emit! (dcm/go-to-viewer params)))))

        active-comments
        (mf/use-fn
         (mf/deps layout)
         (fn []
           (st/emit! :interrupt
                     (dw/clear-edition-mode)
                     (-> (dw/remove-layout-flag :document-history)
                         (vary-meta assoc ::ev/origin "workspace-header"))
                     (dw/select-for-drawing :comments))))

        toggle-comments
        (mf/use-fn
         (mf/deps selected-drawtool)
         (fn [_]
           (if (= selected-drawtool :comments)
             (st/emit! (dwc/clear-drawing))
             (active-comments))))

        toggle-history
        (mf/use-fn
         (mf/deps selected-drawtool)
         (fn []
           (when (= :comments selected-drawtool)
             (st/emit! :interrupt
                       (dw/clear-edition-mode)))

           (st/emit! (-> (dwh/initialize-history)
                         (vary-meta assoc ::ev/origin "workspace-header")))))

        open-share-dialog
        (mf/use-fn
         (mf/deps team)
         (fn []
           (st/emit! (modal/show {:type :invite-members
                                  :team team
                                  :origin :workspace}))))]

    (mf/with-effect [editing?]
      (when ^boolean editing?
        (dom/select-text! (mf/ref-val input-ref))))

    [:div {:class (stl/css :workspace-header-right)}
     [:div {:class (stl/css :users-section)}
      [:& active-sessions]]

     [:& export-progress-widget]

     [:div {:class (stl/css :separator)}]

     [:div {:class (stl/css :zoom-section)}
      [:& zoom-widget-workspace
       {:zoom zoom
        :on-increase on-increase
        :on-decrease on-decrease
        :on-zoom-reset on-zoom-reset
        :on-zoom-fit on-zoom-fit
        :on-zoom-selected on-zoom-selected}]]

     [:div {:class (stl/css :comments-section)}
      [:button {:title (tr "workspace.toolbar.comments" (sc/get-tooltip :add-comment))
                :aria-label (tr "workspace.toolbar.comments" (sc/get-tooltip :add-comment))
                :class (stl/css-case :comments-btn true
                                     :selected (= selected-drawtool :comments))
                :on-click toggle-comments
                :data-tool "comments"
                :style {:position "relative"}}
       i/comments
       (when ^boolean has-unread-comments?
         [:div {:class (stl/css :unread)}])]]

     (when-not ^boolean read-only?
       [:div {:class (stl/css :history-section)}
        [:button
         {:title (tr "workspace.sidebar.history")
          :aria-label (tr "workspace.sidebar.history")
          :class (stl/css-case :selected (contains? layout :document-history)
                               :history-button true)
          :on-click toggle-history}
         i/history]])

     (when display-share-button?
       [:a {:class (stl/css :viewer-btn)
            :title (tr "workspace.header.share")
            :on-click open-share-dialog}
        i/share])

     [:a {:class (stl/css :viewer-btn)
          :title (tr "workspace.header.viewer" (sc/get-tooltip :open-viewer))
          :on-click nav-to-viewer}
      i/play]]))


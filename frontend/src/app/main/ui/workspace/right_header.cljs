;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.right-header
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.events :as ev]
   [app.main.data.shortcuts :as scd]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.context :as ctx]
   [app.main.ui.export :refer [export-progress-widget]]
   [app.main.ui.formats :as fmt]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.presence :refer [active-sessions]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.timers :as ts]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ref:workspace-persistence
  (l/derived :workspace-persistence st/state))

;; --- Persistence state Widget

(mf/defc persistence-state-widget
  {::mf/wrap [mf/memo]}
  []
  (let [{:keys [status]} (mf/deref ref:workspace-persistence)]
    [:div {:class (stl/css :persistence-status-widget)}
     (case status
       :pending
       [:div {:class (stl/css-case :status-icon true
                                   :pending-status true)
              :title (tr "workspace.header.unsaved")}
        i/status-alert]

       :saving
       [:div {:class (stl/css-case :status-icon true
                                   :saving-status true)
              :title (tr "workspace.header.saving")}
        i/status-update]

       :saved
       [:div {:class (stl/css-case :status-icon true
                                   :saved-status true)
              :title (tr "workspace.header.saved")}
        i/status-tick]

       :error
       [:div {:class (stl/css-case :status-icon true
                                   :error-status true)
              :title "There was an error saving the data. Please refresh if this persists."}
        i/status-wrong]

       nil)]))

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
         [:button {:class (stl/css :zoom-btn)
                   :on-click on-decrease}
          [:span {:class (stl/css :zoom-icon)}
           i/remove-icon]]
         [:p {:class (stl/css :zoom-text)} zoom]
         [:button {:class (stl/css :zoom-btn)
                   :on-click on-increase}
          [:span {:class (stl/css :zoom-icon)}
           i/add]]]
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

(mf/defc right-header
  {::mf/wrap-props false}
  [{:keys [file layout page-id]}]
  (let [file-id          (:id file)

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

        nav-to-viewer
        (mf/use-fn
         (mf/deps file-id page-id)
         (fn []
           (let [params {:page-id page-id
                         :file-id file-id
                         :section "interactions"}]
             (st/emit! (dw/go-to-viewer params)))))

        active-comments
        (mf/use-fn
         (fn []
           (st/emit! :interrupt
                     (dw/clear-edition-mode))
           ;; Delay so anything that launched :interrupt can finish
           (ts/schedule 100 #(st/emit! (dw/select-for-drawing :comments)))))

        toggle-comments
        (mf/use-fn
         (mf/deps selected-drawtool)
         (fn [_]
           (if (= :comments selected-drawtool)
             (st/emit! :interrupt)
             (active-comments))))

        toggle-history
        (mf/use-fn
         (mf/deps selected-drawtool)
         (fn []
           (when (= :comments selected-drawtool)
             (st/emit! :interrupt))

           (st/emit! (-> (dw/toggle-layout-flag :document-history)
                         (vary-meta assoc ::ev/origin "workspace-header")))))]

    (mf/with-effect [editing?]
      (when ^boolean editing?
        (dom/select-text! (mf/ref-val input-ref))))

    [:div {:class (stl/css :workspace-header-right)}
     [:div {:class (stl/css :users-section)}
      [:& active-sessions]]

     [:& persistence-state-widget]

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
                :data-tool "comments"}
       i/comments]]

     (when-not ^boolean read-only?
       [:div {:class (stl/css :history-section)}
        [:button
         {:title (tr "workspace.sidebar.history" (sc/get-tooltip :toggle-history))
          :aria-label (tr "workspace.sidebar.history" (sc/get-tooltip :toggle-history))
          :class (stl/css-case :selected (contains? layout :document-history)
                               :history-button true)
          :on-click toggle-history}
         i/history]])

     [:a {:class (stl/css :viewer-btn)
          :title (tr "workspace.header.viewer" (sc/get-tooltip :open-viewer))
          :on-click nav-to-viewer}
      i/play]]))


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.top-toolbar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.media :as cm]
   [app.main.data.events :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.media :as dwm]
   [app.main.data.workspace.path.state :as pst]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.timers :as ts]
   [okulary.core :as l]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(mf/defc image-upload
  {::mf/wrap [mf/memo]}
  []
  (let [ref            (mf/use-ref nil)
        file-id        (mf/use-ctx ctx/current-file-id)

        on-click
        (mf/use-fn
         (fn []
           (st/emit! :interrupt (dw/clear-edition-mode))
           (dom/click (mf/ref-val ref))))

        on-selected
        (mf/use-fn
         (mf/deps file-id)
         (fn [blobs]
           ;; We don't want to add a ref because that redraws the component
           ;; for everychange. Better direct access on the callback.
           (let [vbox   (deref refs/vbox)
                 x      (+ (:x vbox) (/ (:width vbox) 2))
                 y      (+ (:y vbox) (/ (:height vbox) 2))
                 params {:file-id file-id
                         :blobs (seq blobs)
                         :position (gpt/point x y)}]
             (st/emit! (dwm/upload-media-workspace params)))))]
    [:li
     [:button
      {:title (tr "workspace.toolbar.image" (sc/get-tooltip :insert-image))
       :aria-label (tr "workspace.toolbar.image" (sc/get-tooltip :insert-image))
       :on-click on-click
       :class (stl/css :main-toolbar-options-button)}
      i/img
      [:& file-uploader
       {:input-id "image-upload"
        :accept cm/str-image-types
        :multi true
        :ref ref
        :on-selected on-selected}]]]))

(def toolbar-hidden
  (l/derived
   (fn [state]
     (let [visibility (dm/get-in state [:workspace-local :hide-toolbar])
           editing?   (pst/path-editing? state)
           hidden?    (if editing? true visibility)]
       hidden?))
   st/state))

(mf/defc top-toolbar
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [{:keys [layout]}]
  (let [selected-drawtool    (mf/deref refs/selected-drawing-tool)
        edition              (mf/deref refs/selected-edition)

        read-only?           (mf/use-ctx ctx/workspace-read-only?)

        rulers?              (mf/deref refs/rulers?)
        hide-toolbar?        (mf/deref toolbar-hidden)

        interrupt
        (mf/use-fn #(st/emit! :interrupt (dw/clear-edition-mode)))

        select-drawtool
        (mf/use-fn
         (fn [event]
           (let [tool (-> (dom/get-current-target event)
                          (dom/get-data "tool")
                          (keyword))]
             (st/emit! :interrupt (dw/clear-edition-mode))

             ;; Delay so anything that launched :interrupt can finish
             (ts/schedule 100 #(st/emit! (dw/select-for-drawing tool))))))

        toggle-debug-panel
        (mf/use-fn
         (mf/deps layout)
         (fn []
           (let [is-sidebar-closed? (contains? layout :collapse-left-sidebar)]
             (when is-sidebar-closed?
               (st/emit! (dw/toggle-layout-flag :collapse-left-sidebar)))
             (st/emit!
              (dw/remove-layout-flag :shortcuts)
              (-> (dw/toggle-layout-flag :debug-panel)
                  (vary-meta assoc ::ev/origin "workspace-left-toolbar"))))))

        toggle-toolbar
        (mf/use-fn
         (fn [event]
           (dom/blur! (dom/get-target event))
           (st/emit! (dwc/toggle-toolbar-visibility))))]

    (when-not ^boolean read-only?
      [:aside {:class (stl/css-case :main-toolbar true
                                    :main-toolbar-no-rulers (not rulers?)
                                    :main-toolbar-hidden hide-toolbar?)}
       [:ul {:class (stl/css :main-toolbar-options)
             :data-testid "toolbar-options"}
        [:li
         [:button
          {:title (tr "workspace.toolbar.move"  (sc/get-tooltip :move))
           :aria-label (tr "workspace.toolbar.move"  (sc/get-tooltip :move))
           :class (stl/css-case :main-toolbar-options-button true
                                :selected (and (nil? selected-drawtool)
                                               (not edition)))
           :on-click interrupt}
          i/move]]
        [:*
         [:li
          [:button
           {:title (tr "workspace.toolbar.frame" (sc/get-tooltip :draw-frame))
            :aria-label (tr "workspace.toolbar.frame" (sc/get-tooltip :draw-frame))
            :class  (stl/css-case :main-toolbar-options-button true :selected (= selected-drawtool :frame))
            :on-click select-drawtool
            :data-tool "frame"
            :data-testid "artboard-btn"}
           i/board]]
         [:li
          [:button
           {:title (tr "workspace.toolbar.rect" (sc/get-tooltip :draw-rect))
            :aria-label (tr "workspace.toolbar.rect" (sc/get-tooltip :draw-rect))
            :class (stl/css-case :main-toolbar-options-button true :selected (= selected-drawtool :rect))
            :on-click select-drawtool
            :data-tool "rect"
            :data-testid "rect-btn"}
           i/rectangle]]
         [:li
          [:button
           {:title (tr "workspace.toolbar.ellipse" (sc/get-tooltip :draw-ellipse))
            :aria-label (tr "workspace.toolbar.ellipse" (sc/get-tooltip :draw-ellipse))
            :class (stl/css-case :main-toolbar-options-button true :selected (= selected-drawtool :circle))
            :on-click select-drawtool
            :data-tool "circle"
            :data-testid "ellipse-btn"}
           i/elipse]]
         [:li
          [:button
           {:title (tr "workspace.toolbar.text" (sc/get-tooltip :draw-text))
            :aria-label (tr "workspace.toolbar.text" (sc/get-tooltip :draw-text))
            :class (stl/css-case :main-toolbar-options-button true :selected (= selected-drawtool :text))
            :on-click select-drawtool
            :data-tool "text"}
           i/text]]

         [:& image-upload]

         [:li
          [:button
           {:title  (tr "workspace.toolbar.curve" (sc/get-tooltip :draw-curve))
            :aria-label (tr "workspace.toolbar.curve" (sc/get-tooltip :draw-curve))
            :class (stl/css-case :main-toolbar-options-button true :selected (= selected-drawtool :curve))
            :on-click select-drawtool
            :data-tool "curve"
            :data-testid "curve-btn"}
           i/curve]]
         [:li
          [:button
           {:title (tr "workspace.toolbar.path" (sc/get-tooltip :draw-path))
            :aria-label (tr "workspace.toolbar.path" (sc/get-tooltip :draw-path))
            :class (stl/css-case :main-toolbar-options-button true :selected (= selected-drawtool :path))
            :on-click select-drawtool
            :data-tool "path"
            :data-testid "path-btn"}
           i/path]]

         (when (features/active-feature? @st/state "plugins/runtime")
           [:li
            [:button
             {:title (tr "workspace.toolbar.plugins" (sc/get-tooltip :plugins))
              :aria-label (tr "workspace.toolbar.plugins" (sc/get-tooltip :plugins))
              :class (stl/css :main-toolbar-options-button)
              :on-click #(st/emit!
                          (ptk/event ::ev/event {::ev/name "open-plugins-manager" ::ev/origin "workspace:toolbar"})
                          (modal/show :plugin-management {}))
              :data-tool "plugins"
              :data-testid "plugins-btn"}
             i/puzzle]])

         (when *assert*
           [:li
            [:button
             {:title "Debugging tool"
              :class (stl/css-case :main-toolbar-options-button true :selected (contains? layout :debug-panel))
              :on-click toggle-debug-panel}
             i/bug]])]]

       [:button {:title (tr "workspace.toolbar.toggle-toolbar")
                 :aria-label (tr "workspace.toolbar.toggle-toolbar")
                 :class (stl/css :toolbar-handler)
                 :on-click toggle-toolbar}
        [:div {:class (stl/css :toolbar-handler-btn)}]]])))



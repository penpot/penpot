;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.top-toolbar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.geom.point :as gpt]
   [app.config :as cf]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.drawing.common :as dwdc]
   [app.main.data.workspace.mcp :as mcp]
   [app.main.data.workspace.media :as dwm]
   [app.main.data.workspace.path.state :as pst]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown-menu :refer [dropdown-menu* dropdown-menu-item*]]
   [app.main.ui.components.file-uploader :as file-uploader]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.timers :as ts]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private toolbar-hidden-ref
  (l/derived (fn [state]
               (let [visibility      (get-in state [:workspace-local :hide-toolbar])
                     selected        (get-in state [:workspace-local :selected])

                     is-single       (= (count selected) 1)
                     ;; The path edition bar replaces this toolbar.
                     is-path-editing (and is-single (pst/editing? state))
                     is-path-drawing (pst/drawing? state)]

                 (if (or is-path-editing is-path-drawing) true visibility)))
             st/state))

(def grouped-tools
  {:shapes {:default-tool :rect
            :tools {:rect {:icon i/rectangle}
                    :circle {:icon i/ellipse}
                    :line {:icon i/easing-linear}
                    :arrow {:icon i/stroke-arrow}}}
   :free-draw {:default-tool :path
               :tools {:path {:icon i/path}
                       :curve {:icon i/curve}}}})

(defn- tool-label
  [tool]
  (case tool
    :move    (tr "workspace.toolbar.move"    (sc/get-tooltip :move))
    :frame   (tr "workspace.toolbar.frame"   (sc/get-tooltip :draw-frame))
    :rect    (tr "workspace.toolbar.rect"    (sc/get-tooltip :draw-rect))
    :circle  (tr "workspace.toolbar.ellipse" (sc/get-tooltip :draw-ellipse))
    :line    (tr "workspace.toolbar.line"    (sc/get-tooltip :draw-line))
    :arrow   (tr "workspace.toolbar.arrow"   (sc/get-tooltip :draw-arrow))
    :text    (tr "workspace.toolbar.text"    (sc/get-tooltip :draw-text))
    :path    (tr "workspace.toolbar.path"    (sc/get-tooltip :draw-path))
    :image   (tr "workspace.toolbar.image"   (sc/get-tooltip :insert-image))
    :curve   (tr "workspace.toolbar.curve"   (sc/get-tooltip :draw-curve))
    :plugins (tr "workspace.toolbar.plugins" (sc/get-tooltip :plugins))
    :debug   "Debugging tool"
    (name tool)))

(defn- active-group-tool
  [group drawtool]
  (if (contains? (:tools group) drawtool)
    drawtool
    (:default-tool group)))

(defn- is-selected-group
  [group drawtool]
  (contains? (:tools group) drawtool))

(defn- group-menu-label
  [group drawtool]
  (let [tool-id (active-group-tool group drawtool)]
    (str (tr "labels.options") ": " (tool-label tool-id))))

(defn- cancel-timer!
  [timer-ref*]
  (when-let [timer (mf/ref-val timer-ref*)]
    (ts/dispose! timer)
    (mf/set-ref-val! timer-ref* nil)))

(mf/defc group-tool*
  {::mf/private true
   ::mf/wrap [mf/memo]}
  [{:keys [group drawtool on-select-tool]}]
  (let [default-tool*  (mf/use-state (active-group-tool group drawtool))
        default-tool   (deref default-tool*)

        open*          (mf/use-state false)
        open           (deref open*)

        open-timer*    (mf/use-ref nil)
        close-timer*   (mf/use-ref nil)

        default-icon   (:icon (get-in group [:tools default-tool]))
        subtools       (:tools group)
        menu-label     (group-menu-label group drawtool)
        selected       (boolean (is-selected-group group drawtool))

        on-select-tool
        (mf/use-fn
         (fn [event]
           (on-select-tool event)))

        on-display-menu
        (mf/use-fn
         (fn []
           (cancel-timer! close-timer*)
           (cancel-timer! open-timer*)
           (mf/set-ref-val!
            open-timer*
            (ts/schedule 350
                         #(do
                            (reset! open* true)
                            (mf/set-ref-val! open-timer* nil))))))

        on-hide-menu
        (mf/use-fn
         (fn []
           (cancel-timer! open-timer*)
           (cancel-timer! close-timer*)
           (mf/set-ref-val!
            close-timer*
            (ts/schedule 350
                         #(do
                            (reset! open* false)
                            (mf/set-ref-val! close-timer* nil))))))]

    (mf/with-effect []
      (fn []
        (cancel-timer! open-timer*)
        (cancel-timer! close-timer*)))

    (mf/with-effect [drawtool group]
      (reset! default-tool* (active-group-tool group drawtool)))

    [:li {:class (stl/css :toolbar-group)
          :on-pointer-enter on-display-menu
          :on-pointer-leave on-hide-menu}
     [:div {:role "group"
            :aria-label menu-label}
      [:> icon-button* {:variant "ghost"
                        :flyout-indicator true
                        :aria-label (tool-label default-tool)
                        :aria-haspopup true
                        :aria-pressed selected
                        :aria-expanded open
                        :has-tooltip false
                        :icon default-icon
                        :on-click on-select-tool
                        :data-tool (name default-tool)}]

      [:ul {:role "menu"
            :class (stl/css-case :toolbar-group-flyout true
                                 :open open)
            :aria-label menu-label}

       (for [[id {:keys [icon]}] subtools]
         [:li {:key (name id)
               :role "none"}
          [:> icon-button* {:variant "ghost"
                            :tooltip-placement "bottom"
                            :role "menuitemradio"
                            :aria-label (tool-label id)
                            :aria-pressed (= drawtool id)
                            :aria-checked (= drawtool id)
                            :icon icon
                            :on-click on-select-tool
                            :data-tool (name id)}]])]]]))

(mf/defc image-upload-tool*
  {::mf/private true
   ::mf/wrap [mf/memo]}
  []
  (let [ref      (mf/use-ref nil)
        file-id  (mf/use-ctx ctx/current-file-id)

        on-display-uploader
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

    [:*
     [:> icon-button* {:variant "ghost"
                       :tooltip-placement "bottom"
                       :aria-label (tool-label :image)
                       :icon i/img
                       :on-click on-display-uploader}]
     [:& file-uploader/file-uploader {:input-id "image-upload"
                                      :accept dwm/accept-image-types
                                      :multi true
                                      :ref ref
                                      :on-selected on-selected}]]))

(mf/defc mcp-tool*
  {::mf/private true
   ::mf/wrap [mf/memo]}
  [{:keys [is-mcp-connected]}]
  (let [menu-open*   (mf/use-state false)
        menu-open?   (deref menu-open*)

        on-toggle-menu
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (swap! menu-open* not)))

        on-close-menu
        (mf/use-fn
         #(reset! menu-open* false))

        on-connect
        (mf/use-fn
         #(st/emit! (mcp/connect-mcp)
                    (ev/event {::ev/name "connect-mcp-plugin"
                               ::ev/origin "workspace:toolbar"})))]

    [:*
     [:> button* {:variant "ghost"
                  :on-click on-toggle-menu
                  :aria-pressed menu-open?
                  :data-tool "mcp"
                  :data-testid "mcp-btn"}
      [:div {:class (stl/css-case :toolbar-mcp-button true
                                  :selected menu-open?)}
       [:span {:class (stl/css-case :toolbar-mcp-button-dot true
                                    :connected is-mcp-connected)}]
       [:span {:class (stl/css-case :toolbar-mcp-button-label true
                                    :connected is-mcp-connected)}
        (tr "workspace.toolbar.mcp")]]]

     [:div {:class (stl/css :toolbar-mcp-menu)}
      [:> dropdown-menu* {:show menu-open?
                          :on-close on-close-menu
                          :class (stl/css :toolbar-mcp-dropdown)}
       (if is-mcp-connected
         [:li {:class (stl/css :toolbar-mcp-dropdown-info)
               :role "presentation"}
          (tr "workspace.toolbar.mcp-connected")]
         [:> dropdown-menu-item* {:class (stl/css :toolbar-mcp-dropdown-item)
                                  :on-click on-connect}
          (tr "workspace.toolbar.mcp-connect-here")])]]]))

(mf/defc top-toolbar*
  {::mf/wrap [mf/memo]}
  [{:keys [layout]}]
  (let [selected-drawing-tool (mf/deref refs/selected-drawing-tool)
        selected-edition      (mf/deref refs/selected-edition)
        rulers-enabled        (mf/deref refs/rulers?)
        toolbar-hidden        (mf/deref toolbar-hidden-ref)
        mcp                   (mf/deref refs/mcp)

        plugins-enabled? (features/active-feature? @st/state "plugins/runtime")
        read-only?       (mf/use-ctx ctx/workspace-read-only?)

        mcp-conn-status  (get mcp :connection-status)
        mcp-valid-token? (get mcp :token-valid)
        mcp-enabled?     (get mcp :enabled)

        mcp-connected?   (= "connected" mcp-conn-status)
        mcp-show?        (and (contains? cf/flags :mcp)
                              mcp-enabled?
                              mcp-valid-token?)

        separator?       (or plugins-enabled? *assert* mcp-show?)

        on-display-plugins-manager
        (mf/use-fn
         (fn []
           (st/emit! (ev/event {::ev/name "open-plugins-manager"
                                ::ev/origin "workspace:toolbar"})
                     (modal/show :plugin-management {}))))

        on-toggle-debug-panel
        (mf/use-fn
         (mf/deps layout)
         (fn []
           (let [is-sidebar-closed (contains? layout :collapse-left-sidebar)]
             (when is-sidebar-closed
               (st/emit! (dw/toggle-layout-flag :collapse-left-sidebar)))
             (st/emit! (dw/remove-layout-flag :shortcuts)
                       (-> (dw/toggle-layout-flag :debug-panel)
                           (vary-meta assoc ::ev/origin "workspace-left-toolbar"))))))

        on-interrupt
        (mf/use-fn
         (fn []
           (st/emit! :interrupt
                     (dw/clear-edition-mode)
                     (dwdc/clear-drawing))))

        on-select-tool
        (mf/use-fn
         (fn [event]
           (let [tool (-> (dom/get-current-target event)
                          (dom/get-data "tool")
                          (keyword))]
             (st/emit! :interrupt
                       (dw/clear-edition-mode)
                       (dw/select-for-drawing tool)))))

        on-toggle-toolbar
        (mf/use-fn
         (fn [event]
           (dom/blur! (dom/get-target event))
           (st/emit! (dwc/toggle-toolbar-visibility))))]

    (when-not ^boolean read-only?
      [:div {:role "toolbar"
             :aria-label (tr "workspace.toolbar.label")
             :tab-index "0"
             :class (stl/css-case :toolbar true
                                  :no-rulers (not rulers-enabled)
                                  :hidden toolbar-hidden)}
       [:ul {:class (stl/css :toolbar-options)
             :data-testid "toolbar-options"}
        [:li {:class (stl/css :toolbar-option)}
         [:> icon-button* {:variant "ghost"
                           :tooltip-placement "bottom"
                           :aria-pressed (and (nil? selected-drawing-tool)
                                              (not selected-edition))
                           :aria-label (tr "workspace.toolbar.move"  (sc/get-tooltip :move))
                           :icon i/move
                           :on-click on-interrupt}]]

        [:li {:class (stl/css :toolbar-option)}
         [:> icon-button* {:variant "ghost"
                           :tooltip-placement "bottom"
                           :aria-pressed (= selected-drawing-tool :frame)
                           :aria-label (tool-label :frame)
                           :icon i/board
                           :on-click on-select-tool
                           :data-tool "frame"}]]

        [:> group-tool* {:key :shapes
                         :group (get grouped-tools :shapes)
                         :drawtool selected-drawing-tool
                         :on-select-tool on-select-tool}]

        [:li {:class (stl/css :toolbar-option)}
         [:> icon-button* {:variant "ghost"
                           :tooltip-placement "bottom"
                           :aria-pressed (= selected-drawing-tool :text)
                           :aria-label (tool-label :text)
                           :icon i/text
                           :on-click on-select-tool
                           :data-tool "text"}]]

        [:li {:class (stl/css :toolbar-option)}
         [:> image-upload-tool*]]

        [:> group-tool* {:key :free-draw
                         :group (get grouped-tools :free-draw)
                         :drawtool selected-drawing-tool
                         :on-select-tool on-select-tool}]

        (when separator?
          [:div {:class (stl/css :toolbar-separator)}])

        (when plugins-enabled?
          [:li {:class (stl/css :toolbar-option)}
           [:> icon-button* {:variant "ghost"
                             :tooltip-placement "bottom"
                             :aria-label (tool-label :plugins)
                             :icon i/puzzle
                             :on-click on-display-plugins-manager
                             :data-tool "plugins"}]])

        (when *assert*
          [:li {:class (stl/css :toolbar-option)}
           [:> icon-button* {:variant "ghost"
                             :tooltip-placement "bottom"
                             :aria-pressed (contains? layout :debug-panel)
                             :aria-label (tool-label :debug)
                             :icon i/bug
                             :on-click on-toggle-debug-panel}]])

        (when mcp-show?
          [:li {:class (stl/css :toolbar-option)}
           [:> mcp-tool* {:is-mcp-connected mcp-connected?}]])]

       [:button {:title (tr "workspace.toolbar.toggle-toolbar")
                 :aria-label (tr "workspace.toolbar.toggle-toolbar")
                 :class (stl/css :toolbar-handler)
                 :on-click on-toggle-toolbar}
        [:div {:class (stl/css :toolbar-handler-indicator)}]]])))

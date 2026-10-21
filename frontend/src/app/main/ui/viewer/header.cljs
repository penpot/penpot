;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.viewer.header
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.modal :as modal]
   [app.main.data.shortcuts :as scd]
   [app.main.data.viewer :as dv]
   [app.main.data.viewer.shortcuts :as sc]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [app.main.ui.exports.assets :refer [progress-widget]]
   [app.main.ui.formats :as fmt]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.viewer.comments :refer [comments-menu]]
   [app.main.ui.viewer.interactions :refer [flows-menu* interactions-menu*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def fullscreen-ref
  (l/derived (fn [state]
               (dm/get-in state [:viewer-local :fullscreen?]))
             st/state))

(defn open-login-dialog
  []
  (modal/show! :login-register {}))

(mf/defc zoom-widget
  {::mf/memo true
   ::mf/props :obj}
  [{:keys [zoom
           on-increase
           on-decrease
           on-zoom-reset
           on-fullscreen
           on-zoom-fit
           on-zoom-fill]
    :as props}]

  (let [open*           (mf/use-state false)
        open?           (deref open*)
        toggle-dropdown
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (swap! open* not)))

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
           (on-decrease)))]

    [:div {:class (stl/css :zoom-widget-wrapper)}
     [:button {:type "button"
               :class (stl/css-case :zoom-widget true
                                    :selected open?)
               :on-click toggle-dropdown
               :title (tr "workspace.header.zoom")
               :aria-expanded open?}
      [:span {:class (stl/css :label)} (fmt/format-percent zoom)]]
     [:& dropdown {:show open?
                   :on-close close-dropdown}
      [:ul {:class (stl/css :dropdown)}
       [:li  {:class (stl/css :basic-zoom-bar)}
        [:span {:class (stl/css :zoom-btns)}
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "shortcuts.decrease-zoom")
                           :on-click on-decrease
                           :icon i/remove}]
         [:p  {:class (stl/css :zoom-text)}
          (fmt/format-percent zoom)]
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "shortcuts.increase-zoom")
                           :on-click on-increase
                           :icon i/add}]]
        [:> button* {:variant "ghost"
                     :on-click on-zoom-reset}
         (tr "workspace.header.reset-zoom")]]

       [:li {:class (stl/css :zoom-option)
             :on-click on-zoom-fit}
        (tr "workspace.header.zoom-fit")
        [:span  {:class (stl/css :shortcuts)}
         (for [sc (scd/split-sc (sc/get-tooltip :toggle-zoom-style))]
           [:span {:class (stl/css :shortcut-key)
                   :key (dm/str "zoom-fit-" sc)} sc])]]
       [:li {:class (stl/css :zoom-option)
             :on-click on-zoom-fill}
        (tr "workspace.header.zoom-fill")
        [:span  {:class (stl/css :shortcuts)}
         (for [sc (scd/split-sc (sc/get-tooltip :toggle-zoom-style))]
           [:span {:class (stl/css :shortcut-key)
                   :key (dm/str "zoom-fill-" sc)} sc])]]
       [:li {:class (stl/css :zoom-option)
             :on-click on-fullscreen}
        (tr "workspace.header.zoom-full-screen")
        [:span  {:class (stl/css :shortcuts)}
         (for [sc (scd/split-sc (sc/get-tooltip :toggle-fullscreen))]
           [:span {:class (stl/css :shortcut-key)
                   :key (dm/str "zoom-fullscreen-" sc)} sc])]]]]]))

(mf/defc header-options
  [{:keys [section zoom page file index permissions interactions-mode share]}]
  (let [fullscreen?    (mf/deref fullscreen-ref)

        toggle-fullscreen
        (mf/use-fn
         (fn [] (st/emit! dv/toggle-fullscreen)))

        go-to-workspace
        (mf/use-fn
         (mf/deps page)
         (fn []
           (st/emit! (dv/go-to-workspace (:id page)))))

        open-share-dialog
        (mf/use-fn
         (mf/deps page)
         (fn []
           (modal/show! :share-link {:page page :file file})
           (modal/disallow-click-outside!)))

        handle-increase
        (mf/use-fn
         #(st/emit! dv/increase-zoom))

        handle-decrease
        (mf/use-fn
         #(st/emit! dv/decrease-zoom))

        handle-zoom-reset
        (mf/use-fn
         #(st/emit! dv/reset-zoom))

        handle-zoom-fill
        (mf/use-fn
         #(st/emit! dv/zoom-to-fill))

        handle-zoom-fit
        (mf/use-fn
         #(st/emit! dv/zoom-to-fit))]
    (mf/with-effect [permissions share]
      (when (and
             (:in-team permissions)
             (:is-admin permissions)
             share)
        (open-share-dialog)))

    [:div {:class (stl/css :options-zone)}
     [:& progress-widget]

     (case section
       :interactions [:*
                      (when index
                        [:> flows-menu* {:page page :index index}])
                      [:> interactions-menu*
                       {:interactions-mode interactions-mode}]]
       :comments [:& comments-menu]
       [:div {:class (stl/css :view-options)}])

     [:& zoom-widget
      {:zoom zoom
       :on-increase handle-increase
       :on-decrease handle-decrease
       :on-zoom-reset handle-zoom-reset
       :on-zoom-fill handle-zoom-fill
       :on-zoom-fit  handle-zoom-fit
       :on-fullscreen toggle-fullscreen}]

     (when (:in-team permissions)
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "labels.edit")
                         :icon i/curve
                         :on-click go-to-workspace}])

     [:> icon-button* {:variant "ghost"
                       :aria-label (tr "viewer.header.fullscreen")
                       :aria-pressed (boolean fullscreen?)
                       :icon i/expand
                       :on-click toggle-fullscreen}]

     (when (:in-team permissions)
       [:> button* {:variant "primary"
                    :class (stl/css :share-btn)
                    :on-click open-share-dialog}
        (tr "labels.share")])

     (when-not (:is-logged permissions)
       [:> button* {:variant "ghost"
                    :on-click open-login-dialog}
        (tr "labels.log-or-sign")])]))

(mf/defc header-sitemap
  [{:keys [project file page frame toggle-thumbnails shown-thumbnails] :as props}]
  (let [project-name   (:name project)
        file-name      (:name file)
        page-name      (:name page)
        page-id        (:id page)
        frame-name     (:name frame)
        show-dropdown? (mf/use-state false)

        open-dropdown
        (mf/use-fn
         (fn []
           (reset! show-dropdown? true)))

        close-dropdown
        (mf/use-fn
         (fn []
           (reset! show-dropdown? false)))

        navigate-to
        (mf/use-fn
         (fn [page-id]
           (st/emit! (dv/go-to-page page-id))
           (reset! show-dropdown? false)))]

    [:div {:class (stl/css :sitemap-zone)
           :title (tr "viewer.header.sitemap")}
     [:span {:class (stl/css :project-name)} project-name]
     [:div {:class (stl/css :sitemap-text)}
      [:div {:class (stl/css :breadcrumb)}
       [:button {:type "button"
                 :class (stl/css :breadcrumb-btn)
                 :aria-expanded (deref show-dropdown?)
                 :aria-haspopup "menu"
                 :on-click open-dropdown}
        [:span  {:class (stl/css :breadcrumb-text)}
         (dm/str file-name " / " page-name)]
        [:> icon* {:icon-id i/arrow-down :size "s" :class (stl/css :icon)}]]
       [:span "/"]
       [:& dropdown {:show @show-dropdown?
                     :on-close close-dropdown}
        [:ul {:class (stl/css :dropdown-sitemap)}
         (for [id (get-in file [:data :pages])]
           [:li {:class (stl/css-case :dropdown-element true
                                      :selected (= page-id id))
                 :id (dm/str id)
                 :key (dm/str id)
                 :on-click (partial navigate-to id)}
            [:span {:class (stl/css :label)}
             (get-in file [:data :pages-index id :name])]
            (when (= page-id id)
              [:> icon* {:icon-id i/tick :size "s" :class (stl/css :icon-check)}])])]]]
      [:button {:type "button"
                :class (stl/css :current-frame)
                :id "current-frame"
                :aria-expanded (boolean shown-thumbnails)
                :on-click toggle-thumbnails}
       [:span {:class (stl/css :frame-name)} frame-name]
       [:> icon* {:icon-id i/arrow-down :size "s" :class (stl/css :icon)}]]]]))

(def ^:private penpot-logo-icon
  (deprecated-icon/icon-xref :penpot-logo-icon (stl/css :logo-icon)))


(mf/defc header
  [{:keys [project file page frame zoom section permissions index interactions-mode shown-thumbnails share]}]
  (let [go-to-dashboard
        (mf/use-fn
         #(st/emit! (dv/go-to-dashboard)))

        go-to-inspect
        (mf/use-fn
         (mf/deps permissions)
         (fn []
           (if (:is-logged permissions)
             (st/emit! dv/close-thumbnails-panel
                       (dv/go-to-section :inspect))
             (open-login-dialog))))

        navigate
        (mf/use-fn
         (mf/deps permissions)
         (fn [event]
           (let [section (-> (dom/get-current-target event)
                             (dom/get-data "value")
                             (keyword))]
             (if (or (= section :interactions) (:is-logged permissions))
               (st/emit! (dv/go-to-section section))
               (open-login-dialog)))))

        toggle-thumbnails
        (mf/use-fn
         (fn []
           (st/emit! dv/toggle-thumbnails-panel)))


        close-thumbnails
        (mf/use-fn
         (mf/deps shown-thumbnails)
         (fn [_]
           (when shown-thumbnails
             (st/emit! dv/close-thumbnails-panel))))]


    [:header {:class (stl/css-case :viewer-header true
                                   :fullscreen (mf/deref fullscreen-ref))
              :on-click close-thumbnails}
     [:div {:class (stl/css :nav-zone)}
      ;; If the user doesn't have permission we disable the link
      [:a {:class (stl/css :home-link)
           :on-click go-to-dashboard
           :data-testid "penpot-logo-link"
           :style {:cursor (when-not (:in-team permissions) "auto")
                   :pointer-events (when-not (:in-team permissions) "none")}}
       penpot-logo-icon]

      [:& header-sitemap {:project project
                          :file file
                          :page page
                          :frame frame
                          :toggle-thumbnails toggle-thumbnails
                          :shown-thumbnails shown-thumbnails
                          :index index}]]

     [:div {:class (stl/css :mode-zone)}
      [:> icon-button* {:variant "ghost"
                        :on-click navigate
                        :data-value "interactions"
                        :aria-pressed (= section :interactions)
                        :aria-label (tr "viewer.header.interactions-section" (sc/get-tooltip :open-interactions))
                        :icon i/play}]

      (when (or (:in-team permissions)
                (= (:who-comment permissions) "all"))
        [:> icon-button* {:variant "ghost"
                          :on-click navigate
                          :data-value "comments"
                          :aria-pressed (= section :comments)
                          :aria-label (tr "viewer.header.comments-section" (sc/get-tooltip :open-comments))
                          :icon i/comments}])

      (when (or (:in-team permissions)
                (and (= (:type permissions) :share-link)
                     (= (:who-inspect permissions) "all")))
        [:> icon-button* {:variant "ghost"
                          :on-click go-to-inspect
                          :aria-pressed (= section :inspect)
                          :aria-label (tr "viewer.header.inspect-section" (sc/get-tooltip :open-inspect))
                          :icon i/code}])]

     [:& header-options {:section section
                         :permissions permissions
                         :page page
                         :file file
                         :index index
                         :zoom zoom
                         :interactions-mode interactions-mode
                         :share share}]]))

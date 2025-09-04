;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.right-sidebar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.component :as ctk]
   [app.config :as cf]
   [app.main.data.event :as ev]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.inspect.attributes :refer [attributes]]
   [app.main.ui.inspect.code :refer [code]]
   [app.main.ui.inspect.selection-feedback :refer [resolve-shapes]]
   [app.main.ui.inspect.styles :refer [styles-tab*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.shape-icon :as usi]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(defn- get-libraries
  "Retrieve all libraries, including the local file, on workspace or viewer"
  [from]
  (if (= from :workspace)
    (deref refs/libraries)
    (let [viewer-data (deref refs/viewer-data)
          local       (get-in viewer-data [:file :data])
          id          (get local :id)
          libraries   (:libraries viewer-data)]
      (-> libraries
          (assoc id {:id id
                     :data local})))))

(mf/defc right-sidebar*
  [{:keys [frame page objects file selected shapes page-id file-id share-id from on-change-section on-expand]
    :or {from :viewer}}]
  (let [color-space*   (mf/use-state "hex")
        color-space    (deref color-space*)

        section        (mf/use-state #(if (contains? cf/flags :inspect-styles) :styles :info))
        objects        (or objects (:objects page))
        shapes         (or shapes
                           (resolve-shapes objects selected))
        first-shape    (first shapes)
        page-id        (or page-id (:id page))
        file-id        (or file-id (:id file))

        libraries      (get-libraries from)
        main-instance? (ctk/main-instance? first-shape)

        subtitle       (cond
                         (or
                          (ctk/is-variant-container? first-shape)
                          (and (not (ctk/is-variant? first-shape)) main-instance?))
                         (tr "inspect.subtitle.main")
                         (and (ctk/is-variant? first-shape) main-instance?)
                         (tr "labels.variant")
                         (ctk/instance-head? first-shape)
                         (tr "inspect.subtitle.copy"))

        handle-change-tab
        (mf/use-fn
         (mf/deps from on-change-section)
         (fn [new-section]
           (reset! section (keyword new-section))
           (when on-change-section
             (on-change-section (keyword new-section))
             (st/emit!
              (ptk/event ::ev/event {::ev/name "change-inspect-tab" :tab new-section})))))

        handle-expand
        (mf/use-fn
         (mf/deps on-expand)
         (fn []
           (when on-expand (on-expand))))

        navigate-to-help
        (mf/use-fn
         (fn []
           (dom/open-new-window "https://help.penpot.app/user-guide/inspect/")))

        handle-change-color-space
        (mf/use-fn
         (fn [color-space]
           (reset! color-space* color-space)))

        color-spaces
        (mf/with-memo []
          [{:label (tr "inspect.attributes.color.hex")
            :id "hex"}
           {:label (tr "inspect.attributes.color.rgba")
            :id "rgba"}
           {:label (tr "inspect.attributes.color.hsla")
            :id "hsla"}])

        tabs
        (mf/with-memo []
          (if (contains? cf/flags :inspect-styles)
            [{:label (tr "inspect.tabs.styles")
              :id "styles"}
             {:label (tr "inspect.tabs.computed")
              :id "computed"}
             {:label (tr "inspect.tabs.code")
              :data-testid "code"
              :id "code"}]
            [{:label (tr "inspect.tabs.info")
              :id "info"}
             {:label (tr "inspect.tabs.code")
              :data-testid "code"
              :id "code"}]))]

    (mf/use-effect
     (mf/deps shapes handle-change-tab)
     (fn []
       (if (seq shapes)
         (st/emit! (ptk/event ::ev/event {::ev/name "inspect-mode-click-element"}))
         (handle-change-tab (if (contains? cf/flags :inspect-styles) :styles :info)))))

    [:aside {:class (stl/css-case :settings-bar-right true
                                  :viewer-code (= from :viewer))}
     (if (seq shapes)
       [:div {:class (stl/css :tool-windows)}
        [:div {:class (stl/css-case :shape-info true :shape-info-subtitle (some? subtitle))}
         (if (> (count shapes) 1)
           [:*
            [:div {:class (stl/css :layers-icon)}
             [:> icon* {:icon-id i/layers :size "s"}]]
            [:span {:class (stl/css :layer-title)} (tr "inspect.tabs.code.selected.multiple" (count shapes))]]
           [:*
            [:div {:class (stl/css :shape-icon)}
             ;; Use the shape icon utility to get the correct icon for the first shape
             [:> icon* {:icon-id (usi/get-shape-icon first-shape) :size "s"}]]
            ;; Execution time translation strings:
            ;;   (tr "inspect.tabs.code.selected.circle")
            ;;   (tr "inspect.tabs.code.selected.component")
            ;;   (tr "inspect.tabs.code.selected.curve")
            ;;   (tr "inspect.tabs.code.selected.frame")
            ;;   (tr "inspect.tabs.code.selected.group")
            ;;   (tr "inspect.tabs.code.selected.image")
            ;;   (tr "inspect.tabs.code.selected.mask")
            ;;   (tr "inspect.tabs.code.selected.path")
            ;;   (tr "inspect.tabs.code.selected.rect")
            ;;   (tr "inspect.tabs.code.selected.svg-raw")
            ;;   (tr "inspect.tabs.code.selected.text")

            [:div
             (if (some? subtitle)
               [:*
                [:div {:class (stl/css :layer-title :layer-title-with-subtitle)} (:name first-shape)]
                [:div {:class (stl/css :layer-subtitle)} subtitle]]
               [:div
                [:div {:class (stl/css :layer-title)} (:name first-shape)]])]])]

        [:div {:class (stl/css :inspect-content)}
         (if (contains? cf/flags :inspect-styles)
           [:div {:class (stl/css :inspect-tab-switcher)}
            [:span {:class (stl/css :inspect-tab-switcher-label)} (tr "inspect.tabs.switcher.label")]
            [:div {:class (stl/css :inspect-tab-switcher-controls)}
             [:div {:class (stl/css :inspect-tab-switcher-controls-color-space)}
              [:> select* {:options color-spaces
                           :default-selected "hex"
                           :on-change handle-change-color-space}]]
             [:div {:class (stl/css :inspect-tab-switcher-controls-tab)}
              [:> select* {:options tabs
                           :default-selected (name @section)
                           :on-change handle-change-tab}]]]]
           nil)

         (if (contains? cf/flags :inspect-styles)
           [:div {:class (stl/css :inspect-tab :viewer-tab-switcher :viewer-tab-switcher-layout)}
            (case @section
              :styles
              [:> styles-tab* {:color-space color-space
                               :shapes shapes
                               :libraries libraries
                               :file-id file-id}]
              :computed
              [:& attributes {:page-id page-id
                              :objects objects
                              :file-id file-id
                              :frame frame
                              :shapes shapes
                              :from from
                              :libraries libraries
                              :share-id share-id}]

              :code
              [:& code {:frame frame
                        :shapes shapes
                        :on-expand handle-expand
                        :from from}])]
           [:> tab-switcher* {:tabs tabs
                              :selected (name @section)
                              :on-change handle-change-tab
                              :class (stl/css :viewer-tab-switcher)}
            (case @section
              :info
              [:& attributes {:page-id page-id
                              :objects objects
                              :file-id file-id
                              :frame frame
                              :shapes shapes
                              :from from
                              :libraries libraries
                              :share-id share-id}]

              :code
              [:& code {:frame frame
                        :shapes shapes
                        :on-expand handle-expand
                        :from from}])])]]
       [:div {:class (stl/css :empty)}
        [:div {:class (stl/css :code-info)}
         [:span {:class (stl/css :placeholder-icon)}
          deprecated-icon/code]
         [:span {:class (stl/css :placeholder-label)}
          (tr "inspect.empty.select")]]
        [:div {:class (stl/css :help-info)}
         [:span {:class (stl/css :placeholder-icon)}
          deprecated-icon/help]
         [:span {:class (stl/css :placeholder-label)}
          (tr "inspect.empty.help")]]
        [:button {:class (stl/css :more-info-btn)
                  :on-click navigate-to-help}
         (tr "inspect.empty.more-info")]])]))

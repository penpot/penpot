;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.plugins
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.modal :as modal]
   [app.main.ui.components.search-bar :refer [search-bar]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.icons :as i]
   [app.util.http :as http]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(def ^:private close-icon
  (i/icon-xref :close (stl/css :close-icon)))


(mf/defc plugin-entry
  [{:keys [index manifest on-open-plugin on-remove-plugin]}]

  (let [{:keys [host icon url name description]} manifest
        handle-open-click
        (mf/use-callback
         (mf/deps index manifest on-open-plugin)
         (fn []
           (when on-open-plugin
             (on-open-plugin manifest))))

        handle-delete-click
        (mf/use-callback
         (mf/deps index on-remove-plugin)
         (fn []
           (when on-remove-plugin
             (on-remove-plugin index))))]
    [:div {:class (stl/css :plugins-list-element)}
     [:div {:class (stl/css :plugin-icon)}
      (when (some? icon) [:img {:src (dm/str host icon)}])]
     [:div {:class (stl/css :plugin-description)}
      [:div {:class (stl/css :plugin-title)} name]
      [:div {:class (stl/css :plugin-summary)} (d/nilv description "")]]
     [:button {:class (stl/css :open-button)
               :on-click handle-open-click} (tr "workspace.plugins.button-open")]
     [:button {:class (stl/css :trash-button)
               :on-click handle-delete-click} i/delete]]))

(defn load-from-store
  []
  (let [ls (.-localStorage js/window)
        plugins-val (.getItem ls "plugins")]
    (when plugins-val
      (let [plugins-js (.parse js/JSON plugins-val)]
        (js->clj plugins-js {:keywordize-keys true})))))

(defn save-to-store
  [plugins]
  (let [ls (.-localStorage js/window)
        plugins-js (clj->js plugins)
        plugins-val (.stringify js/JSON plugins-js)]
    (.setItem ls "plugins" plugins-val)))

(defn open-plugin!
  [{:keys [name description host code icon permissions]}]
  (.ɵloadPlugin
   js/window #js
   {:name name
    :description description
    :host host
    :code code
    :icon icon
    :permissions (apply array permissions)}))

(mf/defc plugin-management-dialog
  {::mf/register modal/components
   ::mf/register-as :plugin-management}
  []

  (let [plugins-state* (mf/use-state [])
        plugins-state @plugins-state*

        plugin-url* (mf/use-state "")
        plugin-url  @plugin-url*

        input-status* (mf/use-state nil) ;; :error-url :error-manifest :success
        input-status  @input-status*

        error? (contains? #{:error-url :error-manifest} input-status)

        handle-close-dialog
        (mf/use-callback
         (fn []
           (modal/hide!)))

        handle-url-input
        (mf/use-callback
         (fn [value]
           (reset! input-status* nil)
           (reset! plugin-url* value)))

        handle-install-click
        (mf/use-callback
         (mf/deps plugins-state plugin-url)
         (fn []
           (->> (http/send! {:method :get
                             :uri plugin-url
                             :omit-default-headers true
                             :response-type :json})
                (rx/map :body)
                (rx/subs!
                 (fn [body]
                   (let [name (obj/get body "name")
                         desc (obj/get body "description")
                         host (obj/get body "host")
                         code (obj/get body "code")
                         icon (obj/get body "icon")
                         permissions (obj/get body "permissions")

                         new-state
                         (conj plugins-state
                               {:name name
                                :description desc
                                :host host
                                :code code
                                :icon icon
                                :permissions (->> permissions (mapv str))})]
                     (reset! input-status* :success)
                     (reset! plugin-url* "")
                     (reset! plugins-state* new-state)
                     (save-to-store new-state)))
                 (fn [_]
                   (reset! input-status* :error-url))))))

        handle-open-plugin
        (mf/use-callback
         (fn [manifest]
           (open-plugin! manifest)
           (modal/hide!)))

        handle-remove-plugin
        (mf/use-callback
         (mf/deps plugins-state)
         (fn [plugin-index]
           (let [new-state
                 (into []
                       (keep-indexed (fn [idx item]
                                       (when (not= idx plugin-index) item)))
                       plugins-state)]

             (reset! plugins-state* new-state)
             (save-to-store new-state))))]

    (mf/use-effect
     (fn []
       (reset! plugins-state* (d/nilv (load-from-store) []))))

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog)}
      [:button {:class (stl/css :close-btn) :on-click handle-close-dialog} close-icon]
      [:div {:class (stl/css :modal-title)} (tr "workspace.plugins.title")]

      [:div {:class (stl/css :modal-content)}
       [:div {:class (stl/css :top-bar)}
        [:& search-bar {:on-change handle-url-input
                        :value plugin-url
                        :placeholder (tr "workspace.plugins.search-placeholder")
                        :class (stl/css-case :input-error error?)}]

        [:button {:class (stl/css :primary-button)
                  :disabled (empty? plugin-url)
                  :on-click handle-install-click} (tr "workspace.plugins.install")]]

       (when error?
         [:div {:class (stl/css-case :info true :error error?)}
          (tr "workspace.plugins.error.url")])

       [:hr]

       [:& title-bar {:collapsable false
                      :title (tr "workspace.plugins.installed-plugins")}]

       (if (empty? plugins-state)
         [:div {:class (stl/css :plugins-empty)}
          [:div {:class (stl/css :plugins-empty-logo)} i/logo-icon]
          [:div {:class (stl/css :plugins-empty-text)} (tr "workspace.plugins.empty-plugins")]]

         [:div {:class (stl/css :plugins-list)}

          (for [[idx manifest] (d/enumerate plugins-state)]
            [:& plugin-entry {:key (dm/str "plugin-" idx)
                              :index idx
                              :manifest manifest
                              :on-open-plugin handle-open-plugin
                              :on-remove-plugin handle-remove-plugin}])])]]]))

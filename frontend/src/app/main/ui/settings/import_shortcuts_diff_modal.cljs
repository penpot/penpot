;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.settings.import-shortcuts-diff-modal
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.i18n :refer [tr]]
   [app.main.data.dashboard.shortcuts :as dsc]
   [app.main.data.modal :as modal]
   [app.main.data.viewer.shortcuts :as vsc]
   [app.main.data.workspace.path.shortcuts :as psc]
   [app.main.data.workspace.shortcuts :as wsc]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.shortcuts :as ss]
   [app.util.dom :as dom]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private context->defaults
  {:workspace (d/deep-merge psc/shortcuts wsc/shortcuts)
   :dashboard dsc/shortcuts
   :viewer    vsc/shortcuts})

(defn compute-diff
  [imported-shortcuts custom-shortcuts]
  (let [contexts (filter #(contains? imported-shortcuts %) [:workspace :dashboard :viewer])]
    (mapcat
     (fn [ctx]
       (let [imported-ctx (get imported-shortcuts ctx)
             current-ctx  (get custom-shortcuts ctx {})
             defaults-ctx (get context->defaults ctx)]
         (keep
          (fn [[shortcut-key imported-binding]]
            (let [current-binding (get current-ctx shortcut-key)
                  default-cmd     (:command (get defaults-ctx shortcut-key))
                  effective-binding (or current-binding default-cmd)
                  customized?     (and (some? current-binding)
                                       (seq current-binding)
                                       (not= current-binding default-cmd))]
              (when (and (some? imported-binding)
                         (not= effective-binding imported-binding))
                {:context ctx
                 :key     shortcut-key
                 :current effective-binding
                 :imported imported-binding
                 :customized? customized?})))
          imported-ctx)))
     contexts)))

(def ^:private context-order {:workspace 0 :dashboard 1 :viewer 2})

(def ^:private context-name
  {:workspace "Workspace"
   :dashboard "Dashboard"
   :viewer    "Viewer"})

(mf/defc import-shortcuts-diff-modal
  {::mf/register modal/components
   ::mf/register-as :import-shortcuts-diff-modal}
  [{:keys [imported-shortcuts custom-shortcuts all-shortcuts-raw]}]
  (let [diff-entries
        (mf/with-memo [imported-shortcuts custom-shortcuts]
          (->> (compute-diff imported-shortcuts custom-shortcuts)
               (sort-by (fn [e] [(get context-order (:context e) 99)
                                 (name (:key e))]))))

        handle-close-dialog
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (modal/hide))))

        handle-apply
        (mf/use-fn
         (mf/deps imported-shortcuts all-shortcuts-raw)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (ss/import-custom-shortcuts imported-shortcuts all-shortcuts-raw))
           (st/emit! (modal/hide))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog)}
      [:> icon-button* {:class (stl/css :close-btn)
                        :variant "ghost"
                        :aria-label (tr "labels.close")
                        :on-click handle-close-dialog
                        :tooltip-class (stl/css :close-btn-tooltip)
                        :icon i/close}]
      [:div {:class (stl/css :modal-title)}
       (tr "import-shortcuts.diff-modal-title")]

      [:div {:class (stl/css :modal-content)}
       [:div {:class (stl/css :modal-content-text)}
        (tr "import-shortcuts.diff-modal-text")]

       (if (seq diff-entries)
         [:table {:class (stl/css :shortcuts-table)}
          [:thead
           [:tr {:class (stl/css :shortcuts-list-header)}
            [:th {:class (stl/css :shortcut-header-name)}
             (tr "restore-shortcuts.acction")]
            [:th {:class (stl/css :shortcut-header-command)}
             (tr "labels.current")]
            [:th {:class (stl/css :shortcut-header-command)}
             (tr "labels.import")]]]
          [:tbody {:class (stl/css :shortcuts-list-body)}
           (let [last-ctx* (volatile! nil)]
             (for [entry diff-entries]
               (let [{:keys [context key current imported customized?]} entry
                     show-context-label? (not= @last-ctx* context)]
                 (vreset! last-ctx* context)
                 [:* {:key (dm/str (name context) "-" (name key) "-group")}
                  (when show-context-label?
                    [:tr {:key (dm/str "ctx-" (name context))
                          :class (stl/css :context-separator)}
                     [:td {:colSpan 3
                           :class (stl/css :context-label)}
                      (get context-name context)]])
                  [:tr {:key (dm/str (name context) "-" (name key))
                        :class (stl/css :shortcuts-list-item)}
                   [:td {:class (stl/css :shortcut-name)}
                    (ss/translation-keyname :sc key)]
                   [:td {:class (stl/css :shortcut-command)}
                    (if (str/blank? current)
                      [:span {:class (stl/css :shortcut-empty)} "-"]
                      [:> ss/shortcuts-keys* {:content current
                                              :command key
                                              :is-customized customized?
                                              :light-shortcut true
                                              :has-conflict? false}])]
                   [:td {:class (stl/css :shortcut-command)}
                    (if (str/blank? imported)
                      [:span {:class (stl/css :shortcut-empty)} "-"]
                      [:> ss/shortcuts-keys* {:content imported
                                              :command key
                                              :light-shortcut true
                                              :is-customized true
                                              :has-conflict? false}])]]])))]]
         [:div {:class (stl/css :no-changes)}
          (tr "import-shortcuts.no-changes")])]

      [:div {:class (stl/css :modal-footer)}
       [:div {:class (stl/css :action-buttons)}
        [:> button* {:class (stl/css :cancel-button)
                     :variant "secondary"
                     :type "button"
                     :on-click handle-close-dialog}
         (tr "labels.cancel")]
        [:> button* {:class (stl/css :cancel-button)
                     :variant "primary"
                     :type "button"
                     :on-click handle-apply}
         (tr "import-shortcuts.apply")]]]]]))

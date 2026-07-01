(ns app.main.ui.settings.restore-shortcuts-modal
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.i18n :refer [tr]]
   [app.main.data.dashboard.shortcuts]
   [app.main.data.modal :as modal]
   [app.main.data.shortcuts :as ds]
   [app.main.data.viewer.shortcuts]
   [app.main.data.workspace.path.shortcuts]
   [app.main.data.workspace.shortcuts]
   [app.main.data.workspace.shortcuts.customize :as customize]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.shortcuts :as ss]
   [app.util.dom :as dom]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private workspace-shortcuts-raw
  (d/deep-merge app.main.data.workspace.path.shortcuts/shortcuts
                app.main.data.workspace.shortcuts/shortcuts))

(mf/defc restore-all-modal
  {::mf/register modal/components
   ::mf/register-as :restore-all-modal}
  [{:keys [custom-shortcuts]}]
  (let [handle-close-dialog (mf/use-fn
                             (mf/deps)
                             (fn [event]
                               (dom/stop-propagation event)
                               (st/emit! (modal/hide))))

        handle-accept-dialog (mf/use-fn
                              (mf/deps)
                              (fn [event]
                                (dom/stop-propagation event)
                                (st/emit! (customize/reset-all-custom-shortcuts))
                                (st/emit! (modal/hide))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog)}
      [:> icon-button* {:class (stl/css :close-btn)
                        :variant "ghost"
                        :aria-label  (tr "labels.close")
                        :on-click handle-close-dialog
                        :icon i/close}]
      [:div {:class (stl/css :modal-title)}
       "Restore default configuration"]

      [:div {:class (stl/css :modal-content)}
       [:div {:class (stl/css :modal-content-text)}
        "The following shortcuts will be restored to their default values."]

       [:table {:class (stl/css :shortcuts-table)}
        [:thead
         [:tr {:class (stl/css :shortcuts-list-header)}
          [:th {:class (stl/css :shortcut-header-name)}
           "Shortcut name"]
          [:th {:class (stl/css :shortcut-header-command)}
           "Current"]
          [:th {:class (stl/css :shortcut-header-command)}
           "Default"]]]
        [:tbody {:class (stl/css :shortcuts-list-body)}
         (for [shortcut-key (keys custom-shortcuts)]
           (let [default-command (:command (get workspace-shortcuts-raw shortcut-key))

                 default-managed-list    (if (coll? default-command)
                                           default-command
                                           (conj () default-command))

                 default-chars-list      (map ds/split-sc default-managed-list)

                 default-last-element    (last default-chars-list)

                 default-short-char-list (if (= 1 (count default-chars-list))
                                           default-chars-list
                                           (drop-last default-chars-list))

                 default-penultimate     (last default-short-char-list)

                 current-command (or (get custom-shortcuts shortcut-key) default-command)
                 current-managed-list    (if (coll? current-command)
                                           current-command
                                           (conj () current-command))

                 current-chars-list      (map ds/split-sc current-managed-list)

                 current-last-element    (last current-chars-list)

                 current-short-char-list (if (= 1 (count current-chars-list))
                                           current-chars-list
                                           (drop-last current-chars-list))

                 current-penultimate     (last current-short-char-list)]
             [:tr {:key (name shortcut-key)
                   :class (stl/css :shortcuts-list-item)}
              [:td {:class (stl/css :shortcut-name)}
               (ss/translation-keyname :sc shortcut-key)]
              [:td {:class (stl/css :shortcut-command)}
               (for [chars current-short-char-list]
                 [:* {:key (str/join chars)}
                  (for [char chars]
                    [:> ss/converted-chars* {:key (dm/str char "-" (name shortcut-key))
                                             :char char
                                             :class (stl/css :default-command)
                                             :command shortcut-key}])
                  (when (not= chars current-penultimate) [:span {:class (stl/css :space)} ","])])
               (when (not= current-last-element current-penultimate)
                 [:*
                  [:span {:class (stl/css :space)} (tr "shortcuts.or")]
                  (for [char current-last-element]
                    [:> ss/converted-chars* {:key (dm/str char "-" (name shortcut-key))
                                             :char char
                                             :class (stl/css :default-command)
                                             :command shortcut-key}])])]

              [:td {:class (stl/css :shortcut-command)}
               (for [chars default-short-char-list]
                 [:* {:key (str/join chars)}
                  (for [char chars]
                    [:> ss/converted-chars* {:key (dm/str char "-" (name shortcut-key))
                                             :class (stl/css :default-command)
                                             :char char
                                             :command shortcut-key}])
                  (when (not= chars default-penultimate) [:span {:class (stl/css :space)} ","])])
               (when (not= default-last-element default-penultimate)
                 [:*
                  [:span {:class (stl/css :space)} (tr "shortcuts.or")]
                  (for [char default-last-element]
                    [:> ss/converted-chars* {:key (dm/str char "-" (name shortcut-key))
                                             :class (stl/css :default-command)
                                             :char char
                                             :command shortcut-key}])])]]))]]]

      [:div {:class (stl/css :modal-footer)}
       [:div {:class (stl/css :action-buttons)}
        [:> button* {:class (stl/css :cancel-button)
                     :variant "secondary"
                     :type "button"
                     :on-click modal/hide!}
         (tr "labels.cancel")]
        [:> button* {:class (stl/css :cancel-button)
                     :type "button"
                     :variant "primary"
                     :on-click handle-accept-dialog}
         "restore all"]]]]]))
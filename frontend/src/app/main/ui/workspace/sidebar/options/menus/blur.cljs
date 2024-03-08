;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.blur
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as dch]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :refer [numeric-input*]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def blur-attrs [:blur])

(defn create-blur []
  (let [id (uuid/next)]
    {:id id
     :type :layer-blur
     :value 4
     :hidden false}))

(mf/defc blur-menu [{:keys [ids type values]}]
  (let [blur           (:blur values)
        has-value?     (not (nil? blur))

        state*         (mf/use-state {:show-content true
                                      :show-more-options false})
        state          (deref state*)
        open?          (:show-content state)
        more-options?  (:show-more-options state)

        toggle-content (mf/use-fn #(swap! state* update :show-content not))

        toggle-more-options (mf/use-fn #(swap! state* update :show-more-options not))
        hidden? (:hidden blur)

        change!
        (mf/use-fn
         (mf/deps ids)
         (fn [update-fn]
           (st/emit! (dch/update-shapes ids update-fn))))

        handle-add
        (mf/use-fn
         (mf/deps change!)
         (fn []
           (change! #(assoc % :blur (create-blur)))))

        handle-delete
        (mf/use-fn
         (mf/deps change!)
         (fn []
           (change! #(dissoc % :blur))))

        handle-change
        (mf/use-fn
         (mf/deps change!)
         (fn [value]
           (change! #(cond-> %
                       (not (contains? % :blur))
                       (assoc :blur (create-blur))

                       :always
                       (assoc-in [:blur :value] value)))))

        handle-toggle-visibility
        (mf/use-fn
         (mf/deps change!)
         (fn []
           (change! #(update-in % [:blur :hidden] not))))]

    [:div {:class (stl/css :element-set)}
     [:div {:class (stl/css :element-title)}
      [:& title-bar {:collapsable  has-value?
                     :collapsed    (not open?)
                     :on-collapsed toggle-content
                     :title        (case type
                                     :multiple (tr "workspace.options.blur-options.title.multiple")
                                     :group (tr "workspace.options.blur-options.title.group")
                                     (tr "workspace.options.blur-options.title"))
                     :class        (stl/css-case :title-spacing-blur (not has-value?))}
       (when-not has-value?
         [:button {:class (stl/css :add-blur)
                   :on-click handle-add} i/add])]]
     (when (and open? has-value?)
       [:div {:class (stl/css :element-set-content)}
        [:div {:class (stl/css-case :first-row true
                                    :hidden hidden?)}
         [:div {:class (stl/css :blur-info)}
          [:button {:class (stl/css-case :show-more true
                                         :selected more-options?)
                    :on-click toggle-more-options}
           i/menu]
          [:span {:class (stl/css :label)}
           (tr "workspace.options.blur-options.title")]]
         [:div {:class (stl/css :actions)}
          [:button {:class (stl/css :action-btn)
                    :on-click handle-toggle-visibility}
           (if hidden?
             i/hide
             i/shown)]
          [:button {:class (stl/css :action-btn)
                    :on-click handle-delete} i/remove-icon]]]
        (when more-options?
          [:div {:class (stl/css :second-row)}
           [:label {:class (stl/css :label)
                    :for "blur-input-sidebar"}
            (tr "inspect.attributes.blur.value")]
           [:> numeric-input*
            {:className (stl/css :numeric-input)
             :placeholder "--"
             :id "blur-input-sidebar"
             :min "0"
             :on-change handle-change
             :value (:value blur)}]])])]))

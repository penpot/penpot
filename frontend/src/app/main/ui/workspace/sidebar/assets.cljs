;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.assets
  (:require-macros [app.main.style :refer [css]])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.ui.components.context-menu-a11y :refer [context-menu-a11y]]
   [app.main.ui.components.search-bar :refer [search-bar]]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.assets.common :as cmm]
   [app.main.ui.workspace.sidebar.assets.file-library :refer [file-library]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc assets-libraries
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [{:keys [filters]}]
  (let [libraries (mf/deref refs/workspace-libraries)
        libraries (mf/with-memo [libraries]
                    (->> (vals libraries)
                         (remove :is-indirect)
                         (map (fn [file]
                                (update file :data dissoc :pages-index)))
                         (sort-by #(str/lower (:name %)))))]
    (for [file libraries]
      [:& file-library
       {:key (dm/str (:id file))
        :file file
        :local? false
        :default-open? false
        :filters filters}])))

(mf/defc assets-local-library
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [{:keys [filters]}]
  ;; NOTE: as workspace-file is an incomplete view of file (it do not
  ;; contain :data), we need to reconstruct it using workspace-data
  (let [file   (mf/deref refs/workspace-file)
        data   (mf/deref refs/workspace-data)
        data   (mf/with-memo [data]
                 (dissoc data :pages-index))
        file   (mf/with-memo [file data]
                 (assoc file :data data))]

    [:& file-library
     {:file file
      :local? true
      :default-open? true
      :filters filters}]))

(defn- toggle-values
  [v [a b]]
  (if (= v a) b a))

(mf/defc assets-toolbox
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  []
  (let [components-v2   (mf/use-ctx ctx/components-v2)
        read-only?      (mf/use-ctx ctx/workspace-read-only?)
        new-css-system  (mf/use-ctx ctx/new-css-system)
        filters*      (mf/use-state
                       {:term ""
                        :section "all"
                        :ordering :asc
                        :list-style :thumbs
                        :open-menu false})
        filters       (deref filters*)
        term          (:term filters)
        menu-open?    (:open-menu filters)
        section       (:section filters)
        ordering      (:ordering filters)
        reverse-sort? (= :desc ordering)

        toggle-ordering
        (mf/use-fn #(swap! filters* update :ordering toggle-values [:asc :desc]))

        toggle-list-style
        (mf/use-fn #(swap! filters* update :list-style toggle-values [:thumbs :list]))

        on-search-term-change
        (mf/use-fn
         (mf/deps new-css-system)
         (fn [event]
          ;;  NOTE: When old-css-system is removed this function will recibe value and event
          ;;  Let won't be necessary any more
           (let [value (if ^boolean new-css-system
                         event
                         (dom/get-target-val event))]
             (swap! filters* assoc :term value))))

        on-search-clear-click
        (mf/use-fn #(swap! filters* assoc :term ""))

        on-section-filter-change
        (mf/use-fn
         (fn [event]
           (let [value (or (-> (dom/get-target event)
                               (dom/get-value))
                           (as-> (dom/get-current-target event) $
                             (dom/get-attribute $ "data-test")))]
             (swap! filters* assoc :section value :open-menu false))))

        handle-key-down
        (mf/use-fn
         (fn [event]
           (let [enter? (kbd/enter? event)
                 esc?   (kbd/esc? event)
                 node   (dom/event->target event)]

             (when ^boolean enter? (dom/blur! node))
             (when ^boolean esc?   (dom/blur! node)))))

        show-libraries-dialog
        (mf/use-fn
         (fn []
           (modal/show! :libraries-dialog {})
           (modal/allow-click-outside!)))


        on-open-menu
        (mf/use-fn  #(swap! filters* update :open-menu not))

        on-menu-close
        (mf/use-fn #(swap! filters* assoc :open-menu false))

        options [{:option-name    (tr "workspace.assets.box-filter-all")
                  :id             "section-all"
                  :option-handler on-section-filter-change
                  :data-test      "all"}

                 {:option-name    (tr "workspace.assets.components")
                  :id             "section-components"
                  :option-handler on-section-filter-change
                  :data-test      "components"}

                 {:option-name    (tr "workspace.assets.graphics")
                  :id             "section-graphics"
                  :option-handler on-section-filter-change
                  :data-test      "graphics"}

                 {:option-name    (tr "workspace.assets.colors")
                  :id             "section-color"
                  :option-handler on-section-filter-change
                  :data-test      "colors"}

                 {:option-name    (tr "workspace.assets.typography")
                  :id             "section-typography"
                  :option-handler on-section-filter-change
                  :data-test      "typographies"}]]

    (if ^boolean new-css-system
      [:div  {:class  (css :assets-bar)}
       [:div {:class  (css :assets-header)}
        (when-not read-only?
          [:button {:class (css :libraries-button)
                    :on-click #(modal/show! :libraries-dialog {})}
           [:span {:class (css :libraries-icon)}
            i/library-refactor]
           (tr "workspace.assets.libraries")])

        [:div {:class (css :search-wrapper)}
         [:& search-bar {:on-change on-search-term-change
                        :value term
                        :placeholder (tr "workspace.assets.search")}
         [:button
          {:on-click on-open-menu
           :class (dom/classnames (css :section-button) true)}
          i/filter-refactor]]
        [:& context-menu-a11y
         {:on-close on-menu-close
          :selectable true
          :selected section
          :show menu-open?
          :fixed? true
          :min-width? true
          :top 152
          :left 64
          :options options
          :workspace? true}]
         [:button {:class (css :sort-button)
                   :on-click toggle-ordering}
          (if reverse-sort?
            i/asc-sort-refactor
            i/desc-sort-refactor)]]]

       [:& (mf/provider cmm/assets-filters) {:value filters}
        [:& (mf/provider cmm/assets-toggle-ordering) {:value toggle-ordering}
         [:& (mf/provider cmm/assets-toggle-list-style) {:value toggle-list-style}
          [:div {:class (dom/classnames (css :libraries-wrapper) true)}
           [:& assets-local-library {:filters filters}]
           [:& assets-libraries {:filters filters}]]]]]]

      [:div.assets-bar
       [:div.tool-window
        [:div.tool-window-content
         [:div.assets-bar-title
          (tr "workspace.assets.assets")

          (when-not ^boolean read-only?
            [:div.libraries-button {:on-click show-libraries-dialog}
             i/text-align-justify
             (tr "workspace.assets.libraries")])]
         [:div.search-block
          [:input.search-input
           {:placeholder (tr "workspace.assets.search")
            :type "text"
            :value term
            :on-change on-search-term-change
            :on-key-down handle-key-down}]

          (if ^boolean (str/empty? term)
            [:div.search-icon
             i/search]
            [:div.search-icon.close
             {:on-click on-search-clear-click}
             i/close])]

         [:select.input-select {:value (:section filters)
                                :on-change on-section-filter-change}
          [:option {:value "all"} (tr "workspace.assets.box-filter-all")]
          [:option {:value "components"} (tr "workspace.assets.components")]
          (when-not components-v2
            [:option {:value "graphics"} (tr "workspace.assets.graphics")])
          [:option {:value "colors"} (tr "workspace.assets.colors")]
          [:option {:value "typographies"} (tr "workspace.assets.typography")]]]]

       [:& (mf/provider cmm/assets-filters) {:value filters}
        [:& (mf/provider cmm/assets-toggle-ordering) {:value toggle-ordering}
         [:& (mf/provider cmm/assets-toggle-list-style) {:value toggle-list-style}
          [:div.libraries-wrapper
           [:& assets-local-library {:filters filters}]
           [:& assets-libraries {:filters filters}]]]]]])))

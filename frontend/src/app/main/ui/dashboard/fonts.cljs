;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.fonts
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.media :as cm]
   [app.main.data.fonts :as df]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.context-menu-a11y :refer [context-menu-a11y]]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.icons :as i]
   [app.main.ui.notifications.context-notification :refer [context-notification]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- use-set-page-title
  [team section]
  (mf/use-effect
   (mf/deps team)
   (fn []
     (when team
       (let [tname (if (:is-default team)
                     (tr "dashboard.your-penpot")
                     (:name team))]
         (case section
           :fonts (dom/set-html-title (tr "title.dashboard.fonts" tname))
           :providers (dom/set-html-title (tr "title.dashboard.font-providers" tname))))))))

(mf/defc header
  {::mf/wrap [mf/memo]}
  [{:keys [section team] :as props}]
  (use-set-page-title team section)
  [:header {:class (stl/css :dashboard-header)}
   [:div#dashboard-fonts-title {:class (stl/css :dashboard-title)}
    [:h1 (tr "labels.fonts")]]])

(mf/defc font-variant-display-name
  [{:keys [variant]}]
  [:*
   [:span (cm/font-weight->name (:font-weight variant))]
   (when (not= "normal" (:font-style variant))
     [:span " " (str/capital (:font-style variant))])])

(mf/defc fonts-upload
  [{:keys [team installed-fonts] :as props}]
  (let [fonts*    (mf/use-state {})
        fonts     (deref fonts*)
        input-ref (mf/use-ref)
        uploading (mf/use-state #{})

        bad-font-family-tmp?
        (mf/use-fn
         (fn [font]
           (and (contains? font :font-family-tmp)
                (str/blank? (:font-family-tmp font)))))

        disable-upload-all? (some bad-font-family-tmp? (vals fonts))

        handle-click
        (mf/use-fn #(dom/click (mf/ref-val input-ref)))

        handle-selected
        (mf/use-fn
         (mf/deps team installed-fonts)
         (fn [blobs]
           (->> (df/process-upload blobs (:id team))
                (rx/subs! (fn [result]
                            (swap! fonts* df/merge-and-group-fonts installed-fonts result))
                          (fn [error]
                            (js/console.error "error" error))))))

        on-upload
        (mf/use-fn
         (mf/deps team)
         (fn [item]
           (swap! uploading conj (:id item))
           (->> (rp/cmd! :create-font-variant item)
                (rx/delay-at-least 2000)
                (rx/subs! (fn [font]
                            (swap! fonts* dissoc (:id item))
                            (swap! uploading disj (:id item))
                            (st/emit! (df/add-font font)))
                          (fn [error]
                            (js/console.log "error" error))))))

        on-upload-all
        (fn [items]
          (run! on-upload items))

        on-blur-name
        (fn [id event]
          (let [name (dom/get-target-val event)]
            (when-not (str/blank? name)
              (swap! fonts* df/rename-and-regroup id name installed-fonts))))

        on-change-name
        (fn [id event]
          (let [name (dom/get-target-val event)]
            (swap! fonts* update-in [id] #(assoc % :font-family-tmp name))))

        on-delete
        (mf/use-fn
         (mf/deps team)
         (fn [{:keys [id] :as item}]
           (swap! fonts* dissoc id)))

        on-dismiss-all
        (fn [items]
          (run! on-delete items))

        problematic-fonts? (some :height-warning? (vals fonts))

        handle-upload-all
        (mf/use-fn (mf/deps fonts) #(on-upload-all (vals fonts)))

        handle-dismiss-all
        (mf/use-fn (mf/deps fonts) #(on-dismiss-all (vals fonts)))]

    [:div {:class (stl/css :dashboard-fonts-upload)}
     [:div {:class (stl/css :dashboard-fonts-hero)}
      [:div {:class (stl/css :desc)}
       [:h2 (tr "labels.upload-custom-fonts")]
       [:& i18n/tr-html {:label "dashboard.fonts.hero-text1"}]

       [:button {:class (stl/css :btn-primary)
                 :on-click handle-click
                 :tab-index "0"}
        [:span (tr "labels.add-custom-font")]
        [:& file-uploader {:input-id "font-upload"
                           :accept cm/str-font-types
                           :multi true
                           :ref input-ref
                           :on-selected handle-selected}]]

       [:& context-notification {:content (tr "dashboard.fonts.hero-text2")
                                 :type :default
                                 :is-html true}]

       (when problematic-fonts?
         [:& context-notification {:content (tr "dashboard.fonts.warning-text")
                                   :type :warning
                                   :is-html true}])]]

     [:*
      (when (some? (vals fonts))
        [:div {:class (stl/css :font-item :table-row)}
         [:span (tr "dashboard.fonts.fonts-added" (i18n/c (count (vals fonts))))]
         [:div {:class (stl/css :table-field :options)}
          [:button {:class (stl/css-case :btn-primary true
                                         :disabled disable-upload-all?)
                    :on-click handle-upload-all
                    :data-test "upload-all"
                    :disabled disable-upload-all?}
           [:span (tr "dashboard.fonts.upload-all")]]
          [:button {:class (stl/css :btn-secondary)
                    :on-click handle-dismiss-all
                    :data-test "dismiss-all"}
           [:span (tr "dashboard.fonts.dismiss-all")]]]])

      (for [item (sort-by :font-family (vals fonts))]
        (let [uploading? (contains? @uploading (:id item))
              disable-upload? (or uploading?
                                  (bad-font-family-tmp? item))]
          [:div {:class (stl/css :font-item :table-row)
                 :key (:id item)}
           [:div {:class (stl/css :table-field :family)}
            [:input {:type "text"
                     :on-blur #(on-blur-name (:id item) %)
                     :on-change #(on-change-name (:id item) %)
                     :default-value (:font-family item)}]]
           [:div {:class (stl/css :table-field :variants)}
            [:span {:class (stl/css :label)}
             [:& font-variant-display-name {:variant item}]]]

           [:div {:class (stl/css :table-field :filenames)}
            (for [item (:names item)]
              [:span {:key (dm/str "name-" item)} item])]

           [:div {:class (stl/css :table-field :options)}
            (when (:height-warning? item)
              [:span {:class (stl/css :icon :failure)} i/msg-neutral-refactor])

            [:button {:on-click #(on-upload item)
                      :class (stl/css-case :btn-primary true
                                           :upload-button true
                                           :disabled disable-upload?)
                      :disabled disable-upload?}
             (if uploading?
               (tr "labels.uploading")
               (tr "labels.upload"))]
            [:span {:class (stl/css :icon :close)
                    :on-click #(on-delete item)} i/close-refactor]]]))]]))

(mf/defc installed-font
  [{:keys [font-id variants] :as props}]
  (let [font        (first variants)

        variants    (sort-by (fn [item]
                               [(:font-weight item)
                                (if (= "normal" (:font-style item)) 1 2)])
                             variants)

        open-menu?  (mf/use-state false)
        edit?       (mf/use-state false)
        state*      (mf/use-state (:font-family font))
        font-family (deref state*)


        on-change
        (mf/use-callback
         (fn [event]
           (reset! state* (dom/get-target-val event))))

        on-save
        (mf/use-callback
         (mf/deps font-family)
         (fn [_]
           (when-not (str/blank? font-family)
             (st/emit! (df/update-font {:id font-id :name font-family})))
           (reset! edit? false)))

        on-key-down
        (mf/use-callback
         (mf/deps on-save)
         (fn [event]
           (when (kbd/enter? event)
             (on-save event))))

        on-cancel
        (mf/use-callback
         (fn [_]
           (reset! edit? false)
           (reset! state* (:font-family font))))

        delete-font-fn
        (mf/use-callback
         (mf/deps font-id)
         (fn []
           (st/emit! (df/delete-font font-id))))

        delete-variant-fn
        (mf/use-callback
         (fn [id]
           (st/emit! (df/delete-font-variant id))))

        on-delete
        (mf/use-callback
         (mf/deps delete-font-fn)
         (fn []
           (st/emit! (modal/show
                      {:type :confirm
                       :title (tr "modals.delete-font.title")
                       :message (tr "modals.delete-font.message")
                       :accept-label (tr "labels.delete")
                       :on-accept (fn [_props] (delete-font-fn))}))))

        on-delete-variant
        (mf/use-callback
         (mf/deps delete-variant-fn)
         (fn [id]
           (st/emit! (modal/show
                      {:type :confirm
                       :title (tr "modals.delete-font-variant.title")
                       :message (tr "modals.delete-font-variant.message")
                       :accept-label (tr "labels.delete")
                       :on-accept (fn [_props]
                                    (delete-variant-fn id))}))))]

    [:div {:class (stl/css :font-item :table-row)}
     [:div {:class (stl/css :table-field :family)}
      (if @edit?
        [:input {:type "text"
                 :default-value font-family
                 :on-key-down on-key-down
                 :on-change on-change}]
        [:span (:font-family font)])]

     [:div {:class (stl/css :table-field :variants)}
      (for [item variants]
        [:div {:class (stl/css :variant)
               :key (dm/str (:id item) "-variant")}
         [:span {:class (stl/css :label)}
          [:& font-variant-display-name {:variant item}]]
         [:span
          {:class (stl/css :icon :close)
           :on-click #(on-delete-variant (:id item))}
          i/add-refactor]])]

     (if @edit?
       [:div {:class (stl/css :table-field :options)}
        [:button
         {:disabled (str/blank? font-family)
          :on-click on-save
          :class (stl/css-case :btn-primary true
                               :btn-disabled (str/blank? font-family))}
         (tr "labels.save")]
        [:button {:class (stl/css :icon :close)
                  :on-click on-cancel} i/close-refactor]]

       [:div {:class (stl/css :table-field :options)}
        [:span {:class (stl/css :icon)
                :on-click #(reset! open-menu? true)}
         i/menu-refactor]

        [:& context-menu-a11y {:on-close #(reset! open-menu? false)
                               :show @open-menu?
                               :fixed? false
                               :min-width? true
                               :top -15
                               :left -115
                               :options [{:option-name    (tr "labels.edit")
                                          :id             "font-edit"
                                          :option-handler #(reset! edit? true)}
                                         {:option-name    (tr "labels.delete")
                                          :id             "font-delete"
                                          :option-handler on-delete}]
                               :workspace? false}]])]))


(mf/defc installed-fonts
  [{:keys [fonts] :as props}]
  (let [sterm (mf/use-state "")

        matches?
        #(str/includes? (str/lower (:font-family %)) @sterm)

        on-change
        (mf/use-fn
         (fn [event]
           (let [val (dom/get-target-val event)]
             (reset! sterm (str/lower val)))))]

    [:div {:class (stl/css :dashboard-installed-fonts)}
     [:h3 (tr "labels.installed-fonts")]
     [:div {:class (stl/css :installed-fonts-header)}
      [:div {:class (stl/css :table-field :family)} (tr "labels.font-family")]
      [:div {:class (stl/css :table-field :variants)} (tr "labels.font-variants")]
      [:div {:class (stl/css :table-field :search-input)}
       [:input {:placeholder (tr "labels.search-font")
                :default-value ""
                :on-change on-change}]]]

     (cond
       (seq fonts)
       (for [[font-id variants] (->> (vals fonts)
                                     (filter matches?)
                                     (group-by :font-id))]
         [:& installed-font {:key (dm/str font-id "-installed")
                             :font-id font-id
                             :variants variants}])

       (nil? fonts)
       [:div {:class (stl/css :fonts-placeholder)}
        [:div {:class (stl/css :icon)} i/loader]
        [:div {:class (stl/css :label)} (tr "dashboard.loading-fonts")]]

       :else
       [:div {:class (stl/css :fonts-placeholder)}
        [:div {:class (stl/css :icon)} i/text-refactor]
        [:div {:class (stl/css :label)} (tr "dashboard.fonts.empty-placeholder")]])]))

(mf/defc fonts-page
  [{:keys [team] :as props}]
  (let [fonts (mf/deref refs/dashboard-fonts)]
    [:*
     [:& header {:team team :section :fonts}]
     [:section {:class (stl/css :dashboard-container :dashboard-fonts)}
      [:& fonts-upload {:team team :installed-fonts fonts}]
      [:& installed-fonts {:team team :fonts fonts}]]]))

(mf/defc font-providers-page
  [{:keys [team] :as props}]
  [:*
   [:& header {:team team :section :providers}]
   [:section {:class (stl/css :dashboard-container)}
    [:span "font providers"]]])



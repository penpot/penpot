;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.fonts
  (:require
   [app.common.media :as cm]
   [app.main.data.fonts :as df]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.context-menu :refer [context-menu]]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [beicon.core :as rx]
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
  ;; (let [go-fonts
  ;;       (mf/use-callback
  ;;        (mf/deps team)
  ;;        #(st/emit! (rt/nav :dashboard-fonts {:team-id (:id team)})))

  ;;       go-providers
  ;;       (mf/use-callback
  ;;        (mf/deps team)
  ;;        #(st/emit! (rt/nav :dashboard-font-providers {:team-id (:id team)})))]

  (use-set-page-title team section)

  [:header.dashboard-header
   [:div.dashboard-title
    [:h1 (tr "labels.fonts")]]
   [:nav
    #_[:ul
       [:li {:class (when (= section :fonts) "active")}
        [:a {:on-click go-fonts} (tr "labels.custom-fonts")]]
       [:li {:class (when (= section :providers) "active")}
        [:a {:on-click go-providers} (tr "labels.font-providers")]]]]

   [:div]])

(mf/defc font-variant-display-name
  [{:keys [variant]}]
  [:*
   [:span (cm/font-weight->name (:font-weight variant))]
   (when (not= "normal" (:font-style variant))
     [:span " " (str/capital (:font-style variant))])])

(mf/defc fonts-upload
  [{:keys [team installed-fonts] :as props}]
  (let [fonts    (mf/use-state {})
        input-ref (mf/use-ref)

        uploading (mf/use-state #{})

        on-click
        (mf/use-callback #(dom/click (mf/ref-val input-ref)))

        on-selected
        (mf/use-callback
         (mf/deps team installed-fonts)
         (fn [blobs]
           (->> (df/process-upload blobs (:id team))
                (rx/subs (fn [result]
                           (swap! fonts df/merge-and-group-fonts installed-fonts result))
                         (fn [error]
                           (js/console.error "error" error))))))

        on-upload
        (mf/use-callback
         (mf/deps team)
         (fn [item]
           (swap! uploading conj (:id item))
           (->> (rp/mutation! :create-font-variant item)
                (rx/delay-at-least 2000)
                (rx/subs (fn [font]
                           (swap! fonts dissoc (:id item))
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
            (swap! fonts df/rename-and-regroup id name installed-fonts)))

        on-delete
        (mf/use-callback
         (mf/deps team)
         (fn [{:keys [id] :as item}]
           (swap! fonts dissoc id)))

        on-dismiss-all
        (fn [items]
          (run! on-delete items))]

    [:div.dashboard-fonts-upload
     [:div.dashboard-fonts-hero
      [:div.desc
       [:h2 (tr "labels.upload-custom-fonts")]
       [:& i18n/tr-html {:label "dashboard.fonts.hero-text1"}]

       [:div.banner
        [:div.icon i/msg-info]
        [:div.content
         [:& i18n/tr-html {:tag-name "span"
                           :label "dashboard.fonts.hero-text2"}]]]]

      [:div.btn-primary
       {:on-click on-click}
       [:span (tr "labels.add-custom-font")]
       [:& file-uploader {:input-id "font-upload"
                          :accept cm/str-font-types
                          :multi true
                          :ref input-ref
                          :on-selected on-selected}]]]

     [:*
      (when (some? (vals @fonts))
        [:div.font-item.table-row
         [:span (tr "dashboard.fonts.fonts-added" (i18n/c (count (vals @fonts))))]
         [:div.table-field.options
          [:div.btn-primary
           {:on-click #(on-upload-all (vals @fonts)) :data-test "upload-all"}
           [:span (tr "dashboard.fonts.upload-all")]]
          [:div.btn-secondary
           {:on-click #(on-dismiss-all (vals @fonts)) :data-test "dismiss-all"}
           [:span (tr "dashboard.fonts.dismiss-all")]]]])

      (for [item (sort-by :font-family (vals @fonts))]
        (let [uploading? (contains? @uploading (:id item))]
          [:div.font-item.table-row {:key (:id item)}
           [:div.table-field.family
            [:input {:type "text"
                     :on-blur #(on-blur-name (:id item) %)
                     :default-value (:font-family item)}]]
           [:div.table-field.variants
            [:span.label
             [:& font-variant-display-name {:variant item}]]]
           [:div.table-field.filenames
            (for [item (:names item)]
              [:span item])]

           [:div.table-field.options
            [:button.btn-primary.upload-button
             {:on-click #(on-upload item)
              :class (dom/classnames :disabled uploading?)
              :disabled uploading?}
             (if uploading?
               (tr "labels.uploading")
               (tr "labels.upload"))]
            [:span.icon.close {:on-click #(on-delete item)} i/close]]]))]]))

(mf/defc installed-font
  [{:keys [font-id variants] :as props}]
  (let [font       (first variants)

        variants   (sort-by (fn [item]
                              [(:font-weight item)
                               (if (= "normal" (:font-style item)) 1 2)])
                            variants)

        open-menu? (mf/use-state false)
        edit?      (mf/use-state false)
        state      (mf/use-var (:font-family font))

        on-change
        (fn [event]
          (reset! state (dom/get-target-val event)))

        on-save
        (fn [_]
          (let [font-family @state]
            (when-not (str/blank? font-family)
              (st/emit! (df/update-font
                         {:id font-id
                          :name font-family})))
            (reset! edit? false)))

        on-key-down
        (fn [event]
          (when (kbd/enter? event)
            (on-save event)))

        on-cancel
        (fn [_]
          (reset! edit? false)
          (reset! state (:font-family font)))

        delete-font-fn
        (fn [] (st/emit! (df/delete-font font-id)))

        delete-variant-fn
        (fn [id] (st/emit! (df/delete-font-variant id)))

        on-delete
        (fn []
          (st/emit! (modal/show
                     {:type :confirm
                      :title (tr "modals.delete-font.title")
                      :message (tr "modals.delete-font.message")
                      :accept-label (tr "labels.delete")
                      :on-accept (fn [_props] (delete-font-fn))})))

        on-delete-variant
        (fn [id]
          (st/emit! (modal/show
                     {:type :confirm
                      :title (tr "modals.delete-font-variant.title")
                      :message (tr "modals.delete-font-variant.message")
                      :accept-label (tr "labels.delete")
                      :on-accept (fn [_props]
                                   (delete-variant-fn id))})))]

    [:div.font-item.table-row
     [:div.table-field.family
      (if @edit?
        [:input {:type "text"
                 :default-value @state
                 :on-key-down on-key-down
                 :on-change on-change}]
        [:span (:font-family font)])]

     [:div.table-field.variants
      (for [item variants]
        [:div.variant
         [:span.label
          [:& font-variant-display-name {:variant item}]]
         [:span.icon.close
          {:on-click #(on-delete-variant (:id item))}
          i/plus]])]

     [:div]

     (if @edit?
       [:div.table-field.options
        [:button.btn-primary
         {:disabled (str/blank? @state)
          :on-click on-save
          :class (dom/classnames :btn-disabled (str/blank? @state))}
         (tr "labels.save")]
        [:span.icon.close {:on-click on-cancel} i/close]]

       [:div.table-field.options
        [:span.icon {:on-click #(reset! open-menu? true)} i/actions]
        [:& context-menu
         {:on-close #(reset! open-menu? false)
          :show @open-menu?
          :fixed? false
          :top -15
          :left -115
          :options [[(tr "labels.edit") #(reset! edit? true) nil "font-edit"]
                    [(tr "labels.delete") on-delete nil "font-delete"]]}]])]))


(mf/defc installed-fonts
  [{:keys [fonts] :as props}]
  (let [sterm (mf/use-state "")

        matches?
        #(str/includes? (str/lower (:font-family %)) @sterm)

        on-change
        (mf/use-callback
         (fn [event]
           (let [val (dom/get-target-val event)]
             (reset! sterm (str/lower val)))))]

    [:div.dashboard-installed-fonts
     [:h3 (tr "labels.installed-fonts")]
     [:div.installed-fonts-header
      [:div.table-field.family (tr "labels.font-family")]
      [:div.table-field.variants (tr "labels.font-variants")]
      [:div]
      [:div.table-field.search-input
       [:input {:placeholder (tr "labels.search-font")
                :default-value ""
                :on-change on-change
                }]]]

     (cond
       (seq fonts)
       (for [[font-id variants] (->> (vals fonts)
                                     (filter matches?)
                                     (group-by :font-id))]
         [:& installed-font {:key (str font-id)
                             :font-id font-id
                             :variants variants}])

       (nil? fonts)
       [:div.fonts-placeholder
        [:div.icon i/loader]
        [:div.label (tr "dashboard.loading-fonts")]]

       :else
       [:div.fonts-placeholder
        [:div.icon i/text]
        [:div.label (tr "dashboard.fonts.empty-placeholder")]])]))

(mf/defc fonts-page
  [{:keys [team] :as props}]
  (let [fonts (mf/deref refs/dashboard-fonts)]
    [:*
     [:& header {:team team :section :fonts}]
     [:section.dashboard-container.dashboard-fonts
      [:& fonts-upload {:team team :installed-fonts fonts}]
      [:& installed-fonts {:team team :fonts fonts}]]]))

(mf/defc font-providers-page
  [{:keys [team] :as props}]
  [:*
   [:& header {:team team :section :providers}]
   [:section.dashboard-container
    [:span "font providers"]]])

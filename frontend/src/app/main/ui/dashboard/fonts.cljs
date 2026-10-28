;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.dashboard.fonts
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.media :as cm]
   [app.common.schema :as sm]
   [app.common.types.font :as ctf]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.fonts :as df]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.context-menu-a11y :refer [context-menu*]]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.product.empty-placeholder :refer [empty-placeholder*]]
   [app.main.ui.ds.product.loader :refer [loader*]]
   [app.main.ui.notifications.context-notification :refer [context-notification]]
   [app.util.dom :as dom]
   [app.util.http :as http]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private accept-font-types
  (str (str/join "," cm/font-types)
       ;; A workaround to solve a problem with chrome input selector
       ",.ttf,application/font-woff,.woff,.woff2,.otf"))

(defn- use-page-title
  [team section]
  (mf/with-effect [team]
    (when team
      (let [tname (if (:is-default team)
                    (tr "dashboard.personal-projects")
                    (:name team))]
        (case section
          :fonts (dom/set-html-title (tr "title.dashboard.fonts" tname))
          :providers (dom/set-html-title (tr "title.dashboard.font-providers" tname)))))))

(defn- bad-font-family-tmp?
  [font]
  (and (contains? font :font-family-tmp)
       (str/blank? (:font-family-tmp font))))

(defn- handle-font-upload
  [{:keys [id] :as item}]
  (->> (df/upload-font-variant item)
       ;; This delay fixes the UI problem. When the font is uploaded quickly,
       ;; the user only sees a flicker on the button. With a small delay we
       ;; have a clear transition from uploading state to uploaded
       (rx/delay-at-least 2000)
       (rx/map (fn [font]
                 [id font]))))

(mf/defc header*
  {::mf/memo true
   ::mf/private true}
  [{:keys [section team]}]
  (use-page-title team section)
  [:header {:class (stl/css :dashboard-header) :data-testid "dashboard-header"}
   [:div#dashboard-fonts-title {:class (stl/css :dashboard-title)}
    [:h1 (tr "labels.fonts")]]])

(mf/defc font-variant-display-name*
  {::mf/private true}
  [{:keys [variant]}]
  [:span (cm/font-display-variant (:variant-name variant)
                                  (:font-weight variant)
                                  (:font-style variant))])

(mf/defc uploaded-fonts*
  {::mf/private true}
  [{:keys [team installed-fonts]}]
  (let [fonts*     (mf/use-state {})
        fonts      (deref fonts*)
        font-vals  (mf/with-memo [fonts]
                     (->> fonts
                          (into [] (map val))
                          (not-empty)))

        team-id    (:id team)

        input-ref  (mf/use-ref)

        uploading* (mf/use-state #{})
        uploading  (deref uploading*)

        disable-upload-all?
        (some bad-font-family-tmp? fonts)

        problematic-fonts?
        (some :height-warning? (vals fonts))

        on-click
        (mf/use-fn #(dom/click (mf/ref-val input-ref)))

        on-selected
        (mf/use-fn
         (mf/deps team-id installed-fonts)
         (fn [blobs]
           (->> (df/process-upload blobs team-id)
                (rx/subs! (fn [result]
                            (swap! fonts* df/merge-and-group-fonts installed-fonts result))
                          (fn [error]
                            (st/emit! (ntf/error (tr "errors.generic")))
                            (js/console.error "error" error))))))

        on-upload
        (mf/use-fn
         (mf/deps fonts)
         (fn [event]
           (let [id   (-> (dom/get-current-target event)
                          (dom/get-data "id")
                          (uuid/parse))
                 item (get fonts id)]
             (swap! uploading* conj id)
             (->> (handle-font-upload item)
                  (rx/subs! (fn [[previous-id font]]
                              (swap! fonts* dissoc previous-id)
                              (swap! uploading* disj previous-id)
                              (st/emit! (df/add-font font)))
                            (fn [cause]
                              (st/emit! (ntf/error (tr "errors.bad-font" (first (:names item)))))
                              (js/console.error "Unexpected error on uploading font" cause)))))))

        on-blur-name
        (mf/use-fn
         (mf/deps installed-fonts)
         (fn [event]
           (let [target (dom/get-current-target event)
                 id     (-> target
                            (dom/get-data "id")
                            (uuid/parse))
                 name   (dom/get-value target)]
             (when (and (not (str/blank? name))
                        (sm/validate ctf/schema:font-family name))
               (swap! fonts* df/rename-and-regroup id name installed-fonts)))))

        on-change-name
        (mf/use-fn
         (fn [event]
           (let [target (dom/get-current-target event)
                 id     (-> target
                            (dom/get-data "id")
                            (uuid/parse))
                 name   (dom/get-value target)]
             (swap! fonts* update id assoc :font-family-tmp name))))

        on-delete
        (mf/use-fn
         (mf/deps team)
         (fn [event]
           (let [id (-> (dom/get-current-target event)
                        (dom/get-data "id")
                        (uuid/parse))]
             (swap! fonts* dissoc id))))

        on-upload-all
        (mf/use-fn
         (mf/deps font-vals)
         (fn [_]
           (->> (rx/from font-vals)
                (rx/tap #(swap! uploading* conj (:id %)))
                (rx/mapcat (fn [{:keys [id] :as item}]
                             (->> (handle-font-upload item)
                                  (rx/catch (fn [cause]
                                              (st/emit! (ntf/error (tr "errors.bad-font" (first (:names item)))))
                                              (js/console.error "Unexpected error on uploading font" cause)
                                              (rx/of [id nil]))))))

                (rx/subs! (fn [[previous-id font]]
                            (swap! fonts* dissoc previous-id)
                            (swap! uploading* disj previous-id)
                            (when font
                              (st/emit! (df/add-font font))))))))

        on-dismis-all
        (mf/use-fn
         (mf/deps fonts)
         (fn [_]
           (run! #(swap! fonts* dissoc (:id %)) (vals fonts))))]

    [:div {:class (stl/css :dashboard-fonts-upload)}
     [:div {:class (stl/css :dashboard-fonts-hero)}
      [:div {:class (stl/css :desc)}
       [:> heading* {:level 2
                     :typography t/title-large
                     :class (stl/css :hero-title)}
        (tr "labels.upload-custom-fonts")]
       [:> i18n/tr-html* {:content (tr "dashboard.fonts.hero-text1")
                          :class (stl/css :hero-text)}]

       [:> button* {:variant "primary"
                    :type "button"
                    :class (stl/css :add-font-button)
                    :on-click on-click}
        (tr "labels.add-custom-font")
        [:& file-uploader {:input-id "font-upload"
                           :accept accept-font-types
                           :multi true
                           :ref input-ref
                           :on-selected on-selected}]]

       (when-let [url cf/terms-of-service-uri]
         [:& context-notification {:content (tr "dashboard.fonts.hero-text2" url)
                                   :level :default
                                   :is-html true}])

       (when problematic-fonts?
         [:& context-notification {:content (tr "dashboard.fonts.warning-text")
                                   :level :warning
                                   :is-html true}])]]

     [:*
      (when (seq fonts)
        [:div {:class (stl/css :font-item :table-row)}
         [:span (tr "dashboard.fonts.fonts-added" (i18n/c (count fonts)))]
         [:div {:class (stl/css :table-field :options)}
          [:> button* {:variant "primary"
                       :type "button"
                       :class (stl/css :upload-button)
                       :on-click on-upload-all
                       :data-testid "upload-all"
                       :disabled disable-upload-all?}
           (tr "dashboard.fonts.upload-all")]
          [:> button* {:variant "secondary"
                       :type "button"
                       :class (stl/css :dismiss-button)
                       :on-click on-dismis-all
                       :data-testid "dismiss-all"}
           (tr "dashboard.fonts.dismiss-all")]]])

      (for [{:keys [id] :as item} (sort-by :font-family font-vals)]
        (let [uploading?      (contains? uploading id)
              disable-upload? (or uploading? (bad-font-family-tmp? item))]
          [:div {:class (stl/css :font-item :table-row)
                 :key (dm/str id)}
           [:div {:class (stl/css :table-field :family)}
            [:input {:type "text"
                     :class (stl/css :name-input)
                     :data-id (dm/str id)
                     :on-blur on-blur-name
                     :on-change on-change-name
                     :default-value (:font-family item)}]]
           [:div {:class (stl/css :table-field :variants)}
            [:span {:class (stl/css :label)}
             [:> font-variant-display-name* {:variant item}]]]

           [:div {:class (stl/css :table-field :filenames)}
            (for [item (:names item)]
              [:span {:key (dm/str "name-" item)} item])]

           [:div {:class (stl/css :table-field :options)}
            (when (:height-warning? item)
              [:> icon* {:icon-id i/msg-neutral
                         :class (stl/css :warning-icon)}])

            [:> button* {:variant "primary"
                         :type "button"
                         :on-click on-upload
                         :data-id (dm/str id)
                         :class (stl/css :upload-button)
                         :disabled disable-upload?}
             (if ^boolean uploading?
               (tr "labels.uploading")
               (tr "labels.upload"))]
            [:> icon-button* {:variant "ghost"
                              :aria-label (tr "labels.remove")
                              :icon i/close
                              :data-id (dm/str id)
                              :on-click on-delete}]]]))]]))

(mf/defc installed-font-context-menu*
  {::mf/private true}
  [{:keys [is-open on-close on-edit on-download on-delete]}]
  (let [options (mf/with-memo [on-edit on-download on-delete]
                  [{:name    (tr "labels.edit")
                    :id      "font-edit"
                    :handler on-edit}
                   {:name    (tr "labels.download-simple")
                    :id      "font-download"
                    :handler on-download}
                   {:name    (tr "labels.delete")
                    :id      "font-delete"
                    :handler on-delete}])]
    [:> context-menu*
     {:on-close on-close
      :show is-open
      :fixed false
      :min-width true
      :top -21
      :left -115
      :options options}]))

(mf/defc installed-font*
  {::mf/private true
   ::mf/wrap [mf/memo]}
  [{:keys [font-id variants can-edit]}]
  (let [font        (first variants)

        menu-open*  (mf/use-state false)
        menu-open?  (deref menu-open*)
        edition*    (mf/use-state false)
        edition?    (deref edition*)

        state*      (mf/use-state (:font-family font))
        font-family (deref state*)

        variants
        (mf/with-memo [variants]
          (sort-by (fn [item]
                     [(:font-weight item)
                      (if (= "normal" (:font-style item)) 1 2)])
                   variants))

        on-change
        (mf/use-fn
         (fn [event]
           (reset! state* (dom/get-target-val event))))

        on-edit
        (mf/use-fn #(reset! edition* true))

        on-menu-open
        (mf/use-fn #(reset! menu-open* true))

        on-menu-close
        (mf/use-fn #(reset! menu-open* false))

        on-save
        (mf/use-fn
         (mf/deps font-family)
         (fn [_]
           (reset! edition* false)
           (when-not (str/blank? font-family)
             (if (sm/validate ctf/schema:font-family font-family)
               (st/emit! (df/update-font {:id font-id :name font-family}))
               (st/emit! (ntf/error (tr "errors.font-family-invalid-chars")))))))

        on-key-down
        (mf/use-fn
         (mf/deps on-save)
         (fn [event]
           (when (kbd/enter? event)
             (on-save event))))

        on-cancel
        (mf/use-fn
         (fn [_]
           (reset! edition* false)
           (reset! state* (:font-family font))))

        on-delete-font
        (mf/use-fn
         (mf/deps font-id)
         (fn []
           (let [options   {:type :confirm
                            :title (tr "modals.delete-font.title")
                            :message (tr "modals.delete-font.message")
                            :accept-label (tr "labels.delete")
                            :on-accept (fn [_props]
                                         (st/emit! (df/delete-font font-id)))}]
             (st/emit! (modal/show options)))))

        on-download
        (mf/use-fn
         (mf/deps variants)
         (fn [_event]
           (let [variant    (first variants)
                 variant-id (:id variant)
                 multiple?  (> (count variants) 1)
                 cmd        (if multiple? :download-font-family :download-font)
                 params     (if multiple? {:font-id font-id} {:id variant-id})]
             (->> (rp/cmd! cmd params)
                  (rx/mapcat (fn [{:keys [name uri]}]
                               (->> (http/send! {:uri uri :method :get :response-type :blob})
                                    (rx/map :body)
                                    (rx/map (fn [blob] (d/vec2 name blob))))))
                  (rx/subs! (fn [[filename blob]]
                              (dom/trigger-download filename blob))
                            (fn [error]
                              (js/console.error "error downloading font" error)
                              (st/emit! (ntf/error (tr "errors.generic")))))))))

        on-delete-variant
        (mf/use-fn
         (fn [event]
           (let [id      (-> (dom/get-current-target event)
                             (dom/get-data "id")
                             (uuid/parse))
                 options {:type :confirm
                          :title (tr "modals.delete-font-variant.title")
                          :message (tr "modals.delete-font-variant.message")
                          :accept-label (tr "labels.delete")
                          :on-accept (fn [_props]
                                       (st/emit! (df/delete-font-variant id)))}]
             (st/emit! (modal/show options)))))]

    [:div {:class (stl/css :font-item :table-row)}
     [:div {:class (stl/css-case :table-field true
                                 :family true
                                 :is-edition edition?)}
      (if ^boolean edition?
        [:input {:type "text"
                 :class (stl/css :name-input)
                 :auto-focus true
                 :default-value font-family
                 :on-key-down on-key-down
                 :on-change on-change}]
        [:span (:font-family font)])]

     [:div {:class (stl/css :table-field :variants)}
      (for [{:keys [id] :as item} variants]
        [:div {:class (stl/css-case :variant true
                                    :inhert-variant (not can-edit))
               :key (dm/str id)}
         [:span {:class (stl/css :label)}
          [:> font-variant-display-name* {:variant item}]]
         (when can-edit
           [:button {:type "button"
                     :class (stl/css :variant-delete)
                     :aria-label (tr "dashboard.fonts.delete-variant")
                     :title (tr "dashboard.fonts.delete-variant")
                     :data-id (dm/str id)
                     :on-click on-delete-variant}
            [:> icon* {:icon-id i/close
                       :size "s"
                       :aria-hidden true}]])])]

     (if ^boolean edition?
       [:div {:class (stl/css :table-field :options)}
        [:> button* {:variant "primary"
                     :type "button"
                     :class (stl/css :upload-button)
                     :disabled (str/blank? font-family)
                     :on-click on-save}
         (tr "labels.save")]
        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "labels.cancel")
                          :icon i/close
                          :on-click on-cancel}]]

       (when can-edit
         [:div {:class (stl/css :table-field :options)}
          [:> icon-button* {:variant "ghost"
                            :aria-label (tr "labels.options")
                            :aria-haspopup "menu"
                            :aria-expanded menu-open?
                            :icon i/menu
                            :on-click on-menu-open}]

          [:> installed-font-context-menu*
           {:on-close on-menu-close
            :is-open menu-open?
            :on-delete on-delete-font
            :on-download on-download
            :on-edit on-edit}]]))]))

(mf/defc installed-fonts*
  [{:keys [fonts can-edit]}]
  (let [sterm (mf/use-state "")

        matches?
        #(str/includes? (str/lower (:font-family %)) @sterm)

        on-change
        (mf/use-fn
         (fn [event]
           (let [val (dom/get-target-val event)]
             (reset! sterm (str/lower val)))))]

    [:div {:class (stl/css :dashboard-installed-fonts)}
     (cond
       (seq fonts)
       [:*
        [:> heading* {:level 3
                      :typography t/title-small
                      :class (stl/css :installed-fonts-title)}
         (tr "labels.installed-fonts")]
        [:div {:class (stl/css :installed-fonts-header)}
         [:div {:class (stl/css :table-field :family)} (tr "labels.font-family")]
         [:div {:class (stl/css :table-field :variants)} (tr "labels.font-variants")]
         [:div {:class (stl/css :table-field :search-input)}
          [:input {:class (stl/css :search-font-input)
                   :placeholder (tr "labels.search-font")
                   :default-value ""
                   :on-change on-change}]]]
        (for [[font-id variants] (->> (vals fonts)
                                      (filter matches?)
                                      (group-by :font-id))]
          [:> installed-font* {:key (dm/str font-id "-installed")
                               :font-id font-id
                               :can-edit can-edit
                               :variants variants}])]

       (nil? fonts)
       [:div {:class (stl/css :fonts-placeholder)}
        [:> loader* {:width 32
                     :title (tr "labels.loading")}]
        [:div {:class (stl/css :label)} (tr "dashboard.loading-fonts")]]

       :else
       (if ^boolean can-edit
         [:div {:class (stl/css :fonts-placeholder)}
          [:> icon* {:icon-id i/text
                     :size "xl"
                     :class (stl/css :placeholder-icon)}]
          [:div {:class (stl/css :label)} (tr "dashboard.fonts.empty-placeholder")]]

         [:> empty-placeholder*
          {:title (tr "dashboard.fonts.empty-placeholder-viewer")
           :subtitle (tr "dashboard.fonts.empty-placeholder-viewer-sub")
           :type 2}]))]))

(def ^:private ref:fonts
  (l/derived :fonts st/state))

(mf/defc fonts-page*
  [{:keys [team]}]
  (let [fonts       (mf/deref ref:fonts)
        permissions (:permissions team)
        can-edit    (:can-edit permissions)]
    [:*
     [:> header* {:team team :section :fonts}]
     [:section {:class (stl/css :dashboard-container :dashboard-fonts)}
      (when ^boolean can-edit
        [:> uploaded-fonts* {:team team :installed-fonts fonts}])
      [:> installed-fonts*
       {:team team :fonts fonts :can-edit can-edit}]]]))

(mf/defc font-providers-page*
  [{:keys [team]}]
  [:*
   [:> header* {:team team :section :providers}]
   [:section {:class (stl/css :dashboard-container)}
    [:span "font providers"]]])

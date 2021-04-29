;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.dashboard.fonts
  (:require
   ["opentype.js" :as ot]
   [app.common.media :as cm]
   [app.common.uuid :as uuid]
   [app.main.data.dashboard :as dd]
   [app.main.data.dashboard.fonts :as df]
   [app.main.data.modal :as modal]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.components.context-menu :refer [context-menu]]
   [app.main.store :as st]
   [app.main.repo :as rp]
   [app.main.refs :as refs]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.logging :as log]
   [app.util.keyboard :as kbd]
   [app.util.router :as rt]
   [app.util.webapi :as wa]
   [cuerdas.core :as str]
   [beicon.core :as rx]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(log/set-level! :trace)

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
  (let [go-fonts
        (mf/use-callback
         (mf/deps team)
         (st/emitf (rt/nav :dashboard-fonts {:team-id (:id team)})))

        go-providers
        (mf/use-callback
         (mf/deps team)
         (st/emitf (rt/nav :dashboard-font-providers {:team-id (:id team)})))]

    (use-set-page-title team section)

    [:header.dashboard-header
     [:div.dashboard-title
      [:h1 (tr "labels.fonts")]]
     [:nav
      [:ul
       [:li {:class (when (= section :fonts) "active")}
        [:a {:on-click go-fonts} (tr "labels.custom-fonts")]]
       [:li {:class (when (= section :providers) "active")}
        [:a {:on-click go-providers} (tr "labels.font-providers")]]]]

     [:div]]))

(defn- prepare-fonts
  [blobs]
  (letfn [(prepare [{:keys [font type name data] :as params}]
            (let [family  (or (.getEnglishName ^js font "preferredFamily")
                              (.getEnglishName ^js font "fontFamily"))
                  variant (or (.getEnglishName ^js font "preferredSubfamily")
                              (.getEnglishName ^js font "fontSubfamily"))]
              {:content {:data (js/Uint8Array. data)
                         :name name
                         :type type}
               :font-id (str "custom-" (str/slug family))
               :font-family family
               :font-weight (cm/parse-font-weight variant)
               :font-style  (cm/parse-font-style variant)}))

          (parse-mtype [mtype]
            (case mtype
              "application/vnd.oasis.opendocument.formula-template" "font/otf"
              mtype))

          (parse-font [{:keys [data] :as params}]
            (try
              (assoc params :font (ot/parse data))
              (catch :default e
                (log/warn :msg (str/fmt "skiping file %s, unsupported format" (:name params)))
                nil)))

          (read-blob [blob]
            (->> (wa/read-file-as-array-buffer blob)
                 (rx/map (fn [data]
                           {:data data
                            :name (.-name blob)
                            :type (parse-mtype (.-type blob))}))))]

    (->> (rx/from blobs)
         (rx/mapcat read-blob)
         (rx/map parse-font)
         (rx/filter some?)
         (rx/map prepare))))

(mf/defc fonts-upload
  [{:keys [team] :as props}]
  (let [fonts     (mf/use-state {})
        input-ref (mf/use-ref)

        uploading (mf/use-state #{})

        on-click
        (mf/use-callback #(dom/click (mf/ref-val input-ref)))

        font-key-fn
        (mf/use-callback (juxt :font-family :font-weight :font-style))

        on-selected
        (mf/use-callback
         (mf/deps team)
         (fn [blobs]
           (->> (prepare-fonts blobs)
                (rx/subs (fn [{:keys [content] :as font}]
                           (let [key (font-key-fn font)]
                             (swap! fonts update key
                                    (fn [val]
                                      (-> (or val font)
                                          (assoc :team-id (:id team))
                                          (update :id #(or % (uuid/next)))
                                          (update :data assoc (:type content) (:data content))
                                          (update :names (fnil conj #{}) (:name content))
                                          (dissoc :content))))))
                         (fn [error]
                           (js/console.error "error" error))))))

        on-upload
        (mf/use-callback
         (mf/deps team)
         (fn [item]
           (let [key (font-key-fn item)]
             (swap! uploading conj (:id item))
             (->> (rp/mutation! :create-font-variant item)
                  (rx/delay-at-least 2000)
                  (rx/subs (fn [font]
                             (swap! fonts dissoc key)
                             (swap! uploading disj (:id item))
                             (st/emit! (df/add-font font)))
                           (fn [error]
                             (js/console.log "error" error)))))))

        on-delete
        (mf/use-callback
         (mf/deps team)
         (fn [item]
           (swap! fonts dissoc (font-key-fn item))))]

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
       [:span "Add custom font"]
       [:& file-uploader {:input-id "font-upload"
                          :accept cm/str-font-types
                          :multi true
                          :input-ref input-ref
                          :on-selected on-selected}]]]

     [:*
      (for [item (sort-by :font-family (vals @fonts))]
        (let [uploading? (contains? @uploading (:id item))]
          [:div.font-item.table-row {:key (:id item)}
           [:div.table-field.family
            [:input {:type "text"
                     :default-value (:font-family item)}]]
           [:div.table-field.variant
            [:span (cm/font-weight->name (:font-weight item))]
            (when (not= "normal" (:font-style item))
              [:span " " (str/capital (:font-style item))])]
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
  [{:keys [font] :as props}]
  (let [open-menu? (mf/use-state false)
        edit?      (mf/use-state false)
        state      (mf/use-var (:font-family font))

        on-change
        (mf/use-callback
         (mf/deps font)
         (fn [event]
           (reset! state (dom/get-target-val event))))

        on-save
        (mf/use-callback
         (mf/deps font)
         (fn [event]
           (let [font (assoc font :font-family @state)]
             (st/emit! (df/update-font font))
             (reset! edit? false))))

        on-key-down
        (mf/use-callback
         (mf/deps font)
         (fn [event]
           (when (kbd/enter? event)
             (on-save event))))

        on-cancel
        (mf/use-callback
         (mf/deps font)
         (fn [event]
           (reset! edit? false)
           (reset! state (:font-family font))))

        delete-fn
        (mf/use-callback
         (mf/deps font)
         (st/emitf (df/delete-font font)))

        on-delete
        (mf/use-callback
         (mf/deps font)
         (st/emitf (modal/show
                    {:type :confirm
                     :title (tr "modals.delete-font.title")
                     :message (tr "modals.delete-font.message")
                     :accept-label (tr "labels.delete")
                     :on-accept delete-fn})))]


    [:div.font-item.table-row {:key (:id font)}
     [:div.table-field.family
      (if @edit?
        [:input {:type "text"
                 :default-value @state
                 :on-key-down on-key-down
                 :on-change on-change}]
        [:span (:font-family font)])]

     [:div.table-field.variant
      [:span (cm/font-weight->name (:font-weight font))]
      (when (not= "normal" (:font-style font))
        [:span " " (str/capital (:font-style font))])]

     [:div]

     (if @edit?
       [:div.table-field.options
        [:button.btn-primary
         {:disabled (str/blank? @state)
          :on-click on-save
          :class (dom/classnames :btn-disabled (str/blank? @state))}
          "Save"]
        [:span.icon.close {:on-click on-cancel} i/close]]

       [:div.table-field.options
        [:span.icon {:on-click #(reset! open-menu? true)} i/actions]
        [:& context-menu
         {:on-close #(reset! open-menu? false)
          :show @open-menu?
          :fixed? false
          :top -15
          :left -115
          :options [[(tr "labels.edit") #(reset! edit? true)]
                    [(tr "labels.delete") on-delete]]}]])]))


(mf/defc installed-fonts
  [{:keys [team fonts] :as props}]
  (let [sterm (mf/use-state "")

        matches?
        #(str/includes? (str/lower (:font-family %)) @sterm)

        on-change
        (mf/use-callback
         (fn [event]
           (let [val (dom/get-target-val event)]
             (reset! sterm val))))]

    [:div.dashboard-installed-fonts
     [:h3 (tr "labels.installed-fonts")]
     [:div.installed-fonts-header
      [:div.table-field.family (tr "labels.font-family")]
      [:div.table-field.variant (tr "labels.font-variant")]
      [:div]
      [:div.table-field.search-input
       [:input {:placeholder (tr "labels.search-font")
                :default-value ""
                :on-change on-change
                }]]]
     (for [[font-id fonts] (->> fonts
                                (filter matches?)
                                (group-by :font-id))]
       [:div.fonts-group
        (for [font (sort-by (juxt :font-weight :font-style) fonts)]
          [:& installed-font {:key (:id font) :font font}])])]))


(mf/defc fonts-page
  [{:keys [team] :as props}]
  (let [fonts-map (mf/deref refs/dashboard-fonts)
        fonts     (vals fonts-map)]

    (mf/use-effect
     (mf/deps team)
     (st/emitf (df/fetch-fonts team)))

    [:*
     [:& header {:team team :section :fonts}]
     [:section.dashboard-container.dashboard-fonts
      [:& fonts-upload {:team team}]

      (when fonts
        [:& installed-fonts {:team team
                             :fonts fonts}])]]))
(mf/defc font-providers-page
  [{:keys [team] :as props}]
  [:*
   [:& header {:team team :section :providers}]
   [:section.dashboard-container
    [:span "hello world font providers"]]])

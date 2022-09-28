;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.share-link
  (:require
   [app.common.data :as d]
   [app.common.logging :as log]
   [app.config :as cf]
   [app.main.data.common :as dc]
   [app.main.data.events :as ev]
   [app.main.data.messages :as dm]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [app.util.webapi :as wapi]
   [potok.core :as ptk]
   [rumext.v2 :as mf]))

(log/set-level! :warn)

(defn prepare-params
  [{:keys [pages who-comment who-inspect]}]

   {:pages pages
    :who-comment who-comment
    :who-inspect who-inspect})

(mf/defc share-link-dialog
  {::mf/register modal/components
   ::mf/register-as :share-link}
  [{:keys [file page]}]
  (let [current-page page
        slinks    (mf/deref refs/share-links)
        router    (mf/deref refs/router)
        route     (mf/deref refs/route)
        zoom-type (mf/deref refs/viewer-zoom-type)

        link      (mf/use-state nil)
        confirm   (mf/use-state false)
        open-ops  (mf/use-state false)

        opts      (mf/use-state
                   {:pages-mode "current"
                    :all-pages false
                    :pages #{(:id page)}
                    :who-comment "team"
                    :who-inspect "team"})


        close
        (fn [event]
          (dom/prevent-default event)
          (st/emit! (modal/hide))
          (modal/disallow-click-outside!))

        toggle-all
        (fn []
          (reset! confirm false)
          (swap! opts
                 (fn [state]
                   (if (= true (:all-pages state))
                     (-> state
                         (assoc :all-pages false)
                         (assoc :pages #{(:id page)}))
                     (-> state
                         (assoc :all-pages true)
                         (assoc :pages (into #{} (get-in file [:data :pages]))))))))

        mark-checked-page
        (fn [event id]
          (let [target   (dom/get-target event)
                checked? (.-checked ^js target)
                dif-pages? (not= id (first (:pages @opts)))
                no-one-page (< 1 (count (:pages @opts)))
                should-change (or no-one-page dif-pages?)]
            (when should-change
              (reset! confirm false)
              (swap! opts update :pages
                     (fn [pages]
                       (if checked?
                         (conj pages id)
                         (disj pages id)))))))

        create-link
        (fn [_]
          (let [params (prepare-params @opts)
                params (assoc params :file-id (:id file))]
            (st/emit! (dc/create-share-link params)
                      (ptk/event ::ev/event {::ev/name "create-shared-link"
                                             ::ev/origin "viewer"
                                             :can-comment (:who-comment params)
                                             :can-inspect-code (:who-inspect params)}))))

        copy-link
        (fn [_]
          (wapi/write-to-clipboard @link)
          (st/emit! (dm/show {:type :info
                              :content (tr "common.share-link.link-copied-success")
                              :timeout 1000})))

        try-delete-link
        (fn [_]
          (reset! confirm true))

        delete-link
        (fn [_]
          (let [params (prepare-params @opts)
                slink  (d/seek #(= (:flags %) (:flags params)) slinks)]
            (reset! confirm false)
            (st/emit! (dc/delete-share-link slink))))

        manage-open-ops
        (fn [_]
          (swap! open-ops not))

        on-who-change
        (fn [type event]
          (let [target  (dom/get-target event)
                value   (dom/get-value target)
                value   (keyword value)]
            (reset! confirm false)
            (if (= type :comment)
              (swap! opts assoc :who-comment (d/name value))
              (swap! opts assoc :who-inspect (d/name value)))))]

    (mf/use-effect
     (mf/deps file slinks @opts)
     (fn []
       (let [{:keys [pages who-comment who-inspect] :as params} (prepare-params @opts)
             slink  (d/seek #(and (= (:who-inspect %) who-inspect) (= (:who-comment %) who-comment) (= (:pages %) pages)) slinks)
             href   (when slink
                      (let [pparams (:path-params route)
                            qparams (-> (:query-params route)
                                        (assoc  :share-id (:id slink))
                                        (assoc  :index "0"))
                            qparams (if (nil? zoom-type)
                                      (dissoc qparams :zoom)
                                      (assoc qparams :zoom zoom-type))

                            href    (rt/resolve router :viewer pparams qparams)]
                        (assoc @cf/public-uri :fragment href)))]
         (reset! link (some-> href str)))))

    [:div.modal-overlay.transparent.share-modal
     [:div.modal-container.share-link-dialog
      [:div.modal-content.initial
       [:div.title
        [:h2 (tr "common.share-link.title")]
        [:div.modal-close-button
         {:on-click close
          :title (tr "labels.close")}
         i/close]]]
      [:div.modal-content
       [:div.share-link-section
        (when (and (not @confirm) (some? @link))
          [:div.custom-input.with-icon
           [:input {:type "text"
                    :value (or @link "")
                    :placeholder (tr "common.share-link.placeholder")
                    :read-only true}]
           [:div.help-icon {:title (tr "viewer.header.share.copy-link")
                            :on-click copy-link}
            i/copy]])
        [:div.hint-wrapper
         (when (not @confirm) [:div.hint (tr "common.share-link.permissions-hint")])
         (cond
           (true? @confirm)
           [:div.confirm-dialog
            [:div.description (tr "common.share-link.confirm-deletion-link-description")]
            [:div.actions
             [:input.btn-secondary
              {:type "button"
               :on-click #(reset! confirm false)
               :value (tr "labels.cancel")}]
             [:input.btn-warning
              {:type "button"
               :on-click delete-link
               :value (tr "common.share-link.destroy-link")}]]]

           (some? @link)
           [:input.btn-secondary
            {:type "button"
             :class "primary"
             :on-click try-delete-link
             :value (tr "common.share-link.destroy-link")}]

           :else
           [:input.btn-primary
            {:type "button"
             :class "primary"
             :on-click create-link
             :value (tr "common.share-link.get-link")}])]]]
      [:div.modal-content.ops-section
       [:div.manage-permissions
        {:on-click manage-open-ops}
        [:span.icon i/picker-hsv]
        [:div.title (tr "common.share-link.manage-ops")]]
       (when @open-ops
         [:*
          (let [all-selected? (:all-pages @opts)
                pages   (->> (get-in file [:data :pages])
                             (map #(get-in file [:data :pages-index %])))
                selected (:pages @opts)]

            [:*
             [:div.view-mode
              [:div.subtitle
               [:span.icon i/play]
               (tr "common.share-link.permissions-pages")]
              [:div.items
               (if (= 1 (count pages))
                 [:div.input-checkbox.check-primary
                  [:input {:type "checkbox"
                           :id (str "page-" (:id current-page))
                           :on-change #(mark-checked-page % (:id current-page))
                           :checked true}]
                  [:label {:for (str "page-" (:id current-page))} (:name current-page)]
                  [:span  (str  " " (tr "common.share-link.current-tag"))]]

                 [:*
                  [:div.row
                   [:div.input-checkbox.check-primary
                    [:input {:type "checkbox"
                             :id "view-all"
                             :checked all-selected?
                             :name "pages-mode"
                             :on-change toggle-all}]
                    [:label {:for "view-all"} (tr "common.share-link.view-all")]]
                   [:span.count-pages (tr "common.share-link.page-shared" (i18n/c (count selected)))]]

                  [:ul.pages-selection
                   (for [page pages]
                     [:li.input-checkbox.check-primary {:key (str (:id page))}
                      [:input {:type "checkbox"
                               :id (str "page-" (:id page))
                               :on-change #(mark-checked-page % (:id page))
                               :checked (contains? selected (:id page))}]
                      (if (= (:id current-page) (:id page))
                        [:*
                         [:label {:for (str "page-" (:id page))} (:name page)]
                         [:span.current-tag  (str  " " (tr "common.share-link.current-tag"))]]
                        [:label {:for (str "page-" (:id page))} (:name page)])])]])]]])
          [:div.access-mode
           [:div.subtitle
            [:span.icon i/chat]
            (tr "common.share-link.permissions-can-comment")]
           [:div.items
            [:select.input-select {:on-change (partial on-who-change :comment)
                                   :value (:who-comment @opts)}
             [:option {:value "team"}  (tr "common.share-link.team-members")]
             [:option {:value "all"}  (tr "common.share-link.all-users")]]]]
          [:div.inspect-mode
           [:div.subtitle
            [:span.icon i/code]
            (tr "common.share-link.permissions-can-inspect")]
           [:div.items
            [:select.input-select {:on-change (partial on-who-change :inspect)
                                   :value (:who-inspect @opts)}
             [:option {:value "team"}  (tr "common.share-link.team-members")]
             [:option {:value "all"}  (tr "common.share-link.all-users")]]]]])]]]))




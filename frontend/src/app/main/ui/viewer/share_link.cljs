;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.share-link
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.logging :as log]
   [app.config :as cf]
   [app.main.data.common :as dc]
   [app.main.data.events :as ev]
   [app.main.data.messages :as msg]
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

(defn- prepare-params
  [{:keys [pages who-comment who-inspect]}]
  {:pages pages
   :who-comment who-comment
   :who-inspect who-inspect})

(mf/defc share-link-dialog
  {::mf/register modal/components
   ::mf/register-as :share-link
   ::mf/wrap-props false}
  [{:keys [file page]}]
  (let [current-page    page
        current-page-id (:id page)
        slinks          (mf/deref refs/share-links)
        router          (mf/deref refs/router)
        route           (mf/deref refs/route)
        zoom-type       (mf/deref refs/viewer-zoom-type)
        page-ids        (dm/get-in file [:data :pages])

        perms-visible*  (mf/use-state false)
        perms-visible?  (deref perms-visible*)

        confirm*        (mf/use-state false)
        confirm?        (deref confirm*)

        options*        (mf/use-state
                         {:pages-mode "current"
                          :all-pages false
                          :pages #{(:id page)}
                          :who-comment "team"
                          :who-inspect "team"})
        options         (deref options*)

        current-link
        (mf/with-memo [slinks options page-ids]
          (let [{:keys [pages who-comment who-inspect] :as params} (prepare-params options)
                slink  (d/seek #(and (= (:who-inspect %) who-inspect)
                                     (= (:who-comment %) who-comment)
                                     (= (:pages %) pages))
                               slinks)]
            (when slink
              (let [pparams (:path-params route)
                    page-id (d/seek #(contains? (:pages slink) %) page-ids)
                    qparams (-> (:query-params route)
                                (assoc :share-id (:id slink))
                                (assoc :page-id page-id)
                                (assoc :index "0"))
                    qparams (if (nil? zoom-type)
                              (dissoc qparams :zoom)
                              (assoc qparams :zoom zoom-type))

                    href    (rt/resolve router :viewer pparams qparams)]
                (dm/str (assoc cf/public-uri :fragment href))))))

        on-close
        (fn [event]
          (dom/prevent-default event)
          (st/emit! (modal/hide))
          (modal/disallow-click-outside!))

        on-toggle-all
        (fn [_event]
          (reset! confirm* false)
          (swap! options*
                 (fn [state]
                   (if (true? (:all-pages state))
                     (-> state
                         (assoc :all-pages false)
                         (assoc :pages #{(:id page)}))
                     (-> state
                         (assoc :all-pages true)
                         (assoc :pages (into #{} (get-in file [:data :pages]))))))))

        on-mark-checked-page
        (fn [event]
          (let [target         (dom/get-target event)
                checked?       (dom/checked? target)
                page-id        (parse-uuid (dom/get-data target "page-id"))
                dif-pages?     (not= page-id (first (:pages options)))
                no-one-page    (< 1 (count (:pages options)))
                should-change? (or ^boolean no-one-page
                                   ^boolean dif-pages?)]
            (when ^boolean should-change?
              (reset! confirm* false)
              (swap! options*
                     (fn [{:keys [pages] :as state}]
                       (let [pages (if checked?
                                     (conj pages page-id)
                                     (disj pages page-id))]
                         (-> state
                             (assoc :pages pages)
                             (assoc :all-pages (= (count pages) (count page-ids))))))))))

        create-link
        (fn [_]
          (let [params (prepare-params options)
                params (assoc params :file-id (:id file))]
            (st/emit! (dc/create-share-link params)
                      (ptk/event ::ev/event {::ev/name "create-shared-link"
                                             ::ev/origin "viewer"
                                             :can-comment (:who-comment params)
                                             :can-inspect-code (:who-inspect params)}))))

        copy-link
        (fn [_]
          (wapi/write-to-clipboard current-link)
          (st/emit! (msg/show {:type :info
                               :content (tr "common.share-link.link-copied-success")
                               :timeout 1000})))

        try-delete-link
        (fn [_]
          (reset! confirm* true))

        delete-link
        (fn [_]
          (let [params (prepare-params options)
                slink  (d/seek #(= (:flags %) (:flags params)) slinks)]
            (reset! confirm* false)
            (st/emit! (dc/delete-share-link slink))))

        toggle-perms-visibility
        (fn [_]
          (swap! perms-visible* not))

        on-who-change
        (fn [type event]
          (let [target  (dom/get-target event)
                value   (dom/get-value target)
                value   (keyword value)]
            (reset! confirm* false)
            (if (= type :comment)
              (swap! options* assoc :who-comment (d/name value))
              (swap! options* assoc :who-inspect (d/name value)))))]

    [:div.modal-overlay.transparent.share-modal
     [:div.modal-container.share-link-dialog
      [:div.modal-content.initial
       [:div.title
        [:h2 (tr "common.share-link.title")]
        [:div.modal-close-button
         {:on-click on-close
          :title (tr "labels.close")}
         i/close]]]
      [:div.modal-content
       [:div.share-link-section
        (when (and (not confirm?) (some? current-link))
          [:div.custom-input.with-icon
           [:input {:type "text"
                    :value (or current-link "")
                    :placeholder (tr "common.share-link.placeholder")
                    :read-only true}]
           [:div.help-icon {:title (tr "viewer.header.share.copy-link")
                            :on-click copy-link}
            i/copy]])
        [:div.hint-wrapper
         (when (not ^boolean confirm?)
           [:div.hint (tr "common.share-link.permissions-hint")])
         (cond
           (true? confirm?)
           [:div.confirm-dialog
            [:div.description (tr "common.share-link.confirm-deletion-link-description")]
            [:div.actions
             [:input.btn-secondary
              {:type "button"
               :on-click #(reset! confirm* false)
               :value (tr "labels.cancel")}]
             [:input.btn-warning
              {:type "button"
               :on-click delete-link
               :value (tr "common.share-link.destroy-link")}]]]

           (some? current-link)
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
        {:on-click toggle-perms-visibility}
        [:span.icon i/picker-hsv]
        [:div.title (tr "common.share-link.manage-ops")]]
       (when ^boolean perms-visible?
         [:*
          (let [all-selected? (:all-pages options)
                pages         (->> (get-in file [:data :pages])
                                   (map #(get-in file [:data :pages-index %])))
                selected      (:pages options)]
            [:*
             [:div.view-mode
              [:div.subtitle
               [:span.icon i/play]
               (tr "common.share-link.permissions-pages")]
              [:div.items
               (if (= 1 (count pages))
                 [:div.input-checkbox.check-primary
                  [:input {:type "checkbox"
                           :id (dm/str "page-" current-page-id)
                           :data-page-id (dm/str current-page-id)
                           :on-change on-mark-checked-page
                           :checked true}]
                  [:label {:for (str "page-" current-page-id)} (:name current-page)]
                  [:span  (str  " " (tr "common.share-link.current-tag"))]]

                 [:*
                  [:div.row
                   [:div.input-checkbox.check-primary
                    [:input {:type "checkbox"
                             :id "view-all"
                             :checked all-selected?
                             :name "pages-mode"
                             :on-change on-toggle-all}]
                    [:label {:for "view-all"} (tr "common.share-link.view-all")]]
                   [:span.count-pages (tr "common.share-link.page-shared" (i18n/c (count selected)))]]

                  [:ul.pages-selection
                   (for [{:keys [id name]} pages]
                     [:li.input-checkbox.check-primary {:key (dm/str id)}
                      [:input {:type "checkbox"
                               :id (dm/str "page-" id)
                               :data-page-id (dm/str id)
                               :on-change on-mark-checked-page
                               :checked (contains? selected id)}]
                      (if (= current-page-id id)
                        [:*
                         [:label {:for (dm/str "page-" id)} name]
                         [:span.current-tag  (dm/str  " " (tr "common.share-link.current-tag"))]]
                        [:label {:for (dm/str "page-" id)} name])])]])]]])
          [:div.access-mode
           [:div.subtitle
            [:span.icon i/chat]
            (tr "common.share-link.permissions-can-comment")]
           [:div.items
            [:select.input-select {:on-change (partial on-who-change :comment)
                                   :value (:who-comment options)}
             [:option {:value "team"}  (tr "common.share-link.team-members")]
             [:option {:value "all"}  (tr "common.share-link.all-users")]]]]
          [:div.inspect-mode
           [:div.subtitle
            [:span.icon i/code]
            (tr "common.share-link.permissions-can-inspect")]
           [:div.items
            [:select.input-select {:on-change (partial on-who-change :inspect)
                                   :value (:who-inspect options)}
             [:option {:value "team"}  (tr "common.share-link.team-members")]
             [:option {:value "all"}  (tr "common.share-link.all-users")]]]]])]]]))




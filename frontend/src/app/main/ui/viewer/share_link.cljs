;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.viewer.share-link
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.logging :as log]
   [app.main.data.common :as dc]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.refs :as refs]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.checkbox :refer [checkbox*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.controls.utilities.input-field :refer [input-field*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.util.clipboard :as clipboard]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

(log/set-level! :warn)

(defn- link-params
  [{:keys [pages who-comment who-inspect]}]
  {:pages pages
   :who-comment who-comment
   :who-inspect who-inspect})

(defn- link->url
  [router route page-ids zoom-type link]
  (let [page-id (d/seek #(contains? (:pages link) %) page-ids)
        params  (-> (:query-params route)
                    (assoc :share-id (:id link))
                    (assoc :page-id page-id)
                    (assoc :index "0"))
        params  (if (nil? zoom-type)
                  (dissoc params :zoom)
                  (assoc params :zoom zoom-type))]
    (rt/resolve-uri router :viewer params)))

(defn- copy-url-to-clipboard
  [url]
  (clipboard/to-clipboard url)
  (st/emit! (ntf/show {:level :info
                       :type :toast
                       :content (tr "common.share-link.link-copied-success")
                       :timeout 1000})
            (ev/event {::ev/name "copy-share-link"
                       ::ev/origin "viewer"})))

(defn- use-collapsible
  []
  (let [expanded*  (mf/use-state false)
        expanded   (deref expanded*)
        content-id (mf/use-id)
        toggle     (mf/use-fn
                    #(swap! expanded* not))]
    {:expanded expanded
     :content-id content-id
     :toggle toggle}))

(mf/defc collapsible-panel*
  {::mf/private true}
  [{:keys [class title children]}]
  (let [{:keys [expanded content-id toggle]} (use-collapsible)]
    [:div {:class class}
     [:button {:class (stl/css :collapsible-title :collapsible-panel-toggle)
               :type "button"
               :aria-expanded expanded
               :aria-controls content-id
               :on-click toggle}
      [:> icon* {:icon-id (if expanded i/arrow-down i/arrow-right)
                 :class (stl/css :collapsible-title-icon)}]
      title]

     (when ^boolean expanded
       [:div {:id content-id}
        children])]))

(mf/defc permissions-select-row*
  {::mf/private true}
  [{:keys [label value options on-change]}]
  [:div {:class (stl/css :permissions-select-row)}
   [:div {:class (stl/css :permissions-label)} label]
   [:div {:class (stl/css :permissions-control)}
    [:> select* {:default-selected (dm/str value)
                 :options options
                 :has-portal true
                 :aria-label label
                 :on-change on-change}]]])

(mf/defc permissions-page-selection*
  {::mf/private true}
  [{:keys [file page selected on-toggle-all on-checked-page]}]
  (let [pages         (->> (get-in file [:data :pages])
                           (map #(get-in file [:data :pages-index %])))
        page-id       (:id page)
        all-selected? (= selected (into #{} (map :id) pages))]

    [:div {:class (stl/css :permissions-pages-field)}
     [:div {:class (stl/css :permissions-label)}
      (tr "common.share-link.permissions-pages")]
     [:div {:class (stl/css :permissions-control)}
      (if (= 1 (count pages))
        [:div {:class (stl/css :permissions-page-row)}
         [:> checkbox* {:id (dm/str "page-" page-id)
                        :class (stl/css :permissions-checkbox)
                        :label (:name page)
                        :checked true
                        :disabled true}]
         [:span {:class (stl/css :permissions-current-tag)}
          (tr "common.share-link.current-tag")]]

        [:*
         [:div {:class (stl/css :permissions-select-all-row)}
          [:> checkbox* {:id "view-all"
                         :class (stl/css :permissions-checkbox)
                         :label (tr "common.share-link.view-all")
                         :checked all-selected?
                         :on-change on-toggle-all}]
          [:span {:class (stl/css :permissions-page-count)}
           (tr "common.share-link.page-shared" (i18n/c (count selected)))]]

         [:ul {:class (stl/css :permissions-pages-selection)}
          (for [{:keys [id name]} pages]
            (let [checked?       (contains? selected id)
                  only-selected? (and checked? (= 1 (count selected)))]
              [:li {:class (stl/css :permissions-page-row)
                    :key (dm/str id)}
               [:> checkbox* {:id (dm/str "page-" id)
                              :class (stl/css :permissions-checkbox)
                              :label name
                              :checked checked?
                              :disabled only-selected?
                              :on-change #(on-checked-page id %)}]
               (when (= page-id id)
                 [:span {:class (stl/css :permissions-current-tag)}
                  (tr "common.share-link.current-tag")])]))]])]]))

(mf/defc permissions-section*
  {::mf/private true}
  [{:keys [file page link-options on-toggle-all on-checked-page
           on-comment-change on-inspect-change]}]
  (let [selected    (:pages link-options)
        permissions [{:id "team" :label (tr "common.share-link.team-members")}
                     {:id "all"  :label (tr "common.share-link.all-users")}]]

    [:> collapsible-panel* {:class (stl/css :permissions-section)
                            :title (tr "common.share-link.manage-ops")}
     [:div {:class (stl/css :permissions-content)}
      [:> permissions-page-selection* {:file file
                                       :page page
                                       :selected selected
                                       :on-toggle-all on-toggle-all
                                       :on-checked-page on-checked-page}]

      [:> permissions-select-row* {:label (tr "common.share-link.permissions-can-comment")
                                   :value (:who-comment link-options)
                                   :options permissions
                                   :on-change on-comment-change}]

      [:> permissions-select-row* {:label (tr "common.share-link.permissions-can-inspect")
                                   :value (:who-inspect link-options)
                                   :options permissions
                                   :on-change on-inspect-change}]]]))

(mf/defc destroy-link-confirmation*
  {::mf/private true}
  [{:keys [on-cancel on-destroy]}]
  (let [on-key-down
        (fn [event]
          (when (kbd/esc? event)
            (dom/stop-propagation event)
            (on-cancel event)))]

    [:div {:class (stl/css :share-link-confirm)
           :role "alertdialog"
           :aria-modal true
           :aria-labelledby "share-link-title"
           :aria-describedby "share-link-confirm-description"
           :tab-index "-1"
           :on-key-down on-key-down}
     [:div {:id "share-link-confirm-description"
            :class (stl/css :share-link-confirm-description)}
      (tr "common.share-link.confirm-deletion-link-description")]
     [:div {:class (stl/css :share-link-confirm-actions)}
      [:> button* {:variant "secondary"
                   :type "button"
                   :on-click on-cancel}
       (tr "labels.cancel")]
      [:> button* {:variant "destructive"
                   :type "button"
                   :on-click on-destroy}
       (tr "common.share-link.destroy-link")]]]))

(mf/defc share-link-content*
  {::mf/private true}
  [{:keys [file page on-request-destroy]}]
  (let [share-links      (mf/deref refs/share-links)
        router           (mf/deref refs/router)
        route            (mf/deref refs/route)
        zoom-type        (mf/deref refs/viewer-zoom-type)

        page-ids         (dm/get-in file [:data :pages])
        all-page-ids     (into #{} page-ids)

        link-options*    (mf/use-state
                          {:pages #{(:id page)}
                           :who-comment "team"
                           :who-inspect "team"})
        link-options     (deref link-options*)

        current-share-link
        (mf/with-memo [share-links link-options]
          (let [{:keys [pages who-comment who-inspect]} (link-params link-options)]
            (d/seek #(and (= (:who-inspect %) who-inspect)
                          (= (:who-comment %) who-comment)
                          (= (:pages %) pages))
                    share-links)))

        current-share-url
        (mf/with-memo [current-share-link page-ids route router zoom-type]
          (when (some? current-share-link)
            (link->url router route page-ids zoom-type current-share-link)))

        on-toggle-all
        (fn [_event]
          (swap! link-options*
                 (fn [{:keys [pages] :as state}]
                   (if (= pages all-page-ids)
                     (assoc state :pages #{(:id page)})
                     (assoc state :pages all-page-ids)))))

        on-checked-page
        (fn [page-id event]
          (let [checked? (dom/checked? (dom/get-target event))]
            (swap! link-options*
                   (fn [{:keys [pages] :as state}]
                     (let [pages (if checked?
                                   (conj pages page-id)
                                   (disj pages page-id))]
                       (if (seq pages)
                         (assoc state :pages pages)
                         state))))))

        on-create-link
        (fn [_]
          (let [params (link-params link-options)
                params (assoc params :file-id (:id file))]
            (st/emit! (dc/create-share-link params)
                      (ev/event {::ev/name "create-share-link"
                                 ::ev/origin "viewer"
                                 :can-comment (:who-comment params)
                                 :can-inspect-code (:who-inspect params)}))))

        on-copy-current-share-url
        (mf/use-fn
         (mf/deps current-share-url)
         (fn [_]
           (copy-url-to-clipboard current-share-url)))

        on-destroy-current-share-link
        (mf/use-fn
         (mf/deps current-share-link on-request-destroy)
         (fn [_]
           (on-request-destroy current-share-link)))

        on-inspect-change
        (fn [value]
          (swap! link-options* assoc :who-inspect value))

        on-comment-change
        (fn [value]
          (swap! link-options* assoc :who-comment value))]

    [:div {:class (stl/css :share-link-content)}
     [:div {:class (stl/css :share-link-form)}
      (when (some? current-share-url)
        [:div {:class (stl/css :share-link-url)}
         [:> input-field* {:id "share-link-url-input"
                           :type "text"
                           :class (stl/css :share-link-url-input)
                           :value (or current-share-url "")
                           :placeholder (tr "common.share-link.placeholder")
                           :read-only true}]
         [:> icon-button* {:variant "secondary"
                           :class (stl/css :share-link-url-button)
                           :aria-label (tr "viewer.header.share.copy-link")
                           :icon i/clipboard
                           :on-click on-copy-current-share-url}]])

      [:div {:class (stl/css :share-link-hint-row)}
       [:div {:class (stl/css :share-link-hint)} (tr "common.share-link.permissions-hint")]
       (if (some? current-share-link)
         [:> button* {:variant "destructive"
                      :type "button"
                      :on-click on-destroy-current-share-link}
          (tr "common.share-link.destroy-link")]
         [:> button* {:variant "primary"
                      :type "button"
                      :on-click on-create-link}
          (tr "common.share-link.get-link")])]]

     [:> permissions-section* {:file file
                               :page page
                               :link-options link-options
                               :on-toggle-all on-toggle-all
                               :on-checked-page on-checked-page
                               :on-comment-change on-comment-change
                               :on-inspect-change on-inspect-change}]]))

(mf/defc share-link-dialog
  {::mf/register modal/components
   ::mf/register-as :share-link
   ::mf/wrap-props false}
  [{:keys [file page]}]
  (let [link-to-destroy* (mf/use-state nil)
        link-to-destroy  (deref link-to-destroy*)

        on-close
        (fn [event]
          (dom/prevent-default event)
          (st/emit! (modal/hide))
          (modal/disallow-click-outside!))

        on-request-destroy
        (mf/use-fn
         (fn [link]
           (reset! link-to-destroy* link)))

        on-destroy-link
        (fn [_]
          (let [link link-to-destroy]
            (reset! link-to-destroy* nil)
            (st/emit! (dc/delete-share-link link))))

        on-cancel-destroy
        (fn [_]
          (reset! link-to-destroy* nil))]

    [:div {:class (stl/css :share-link-modal)}
     [:div {:class (stl/css :share-link-dialog)
            :role "dialog"
            :aria-modal true
            :aria-labelledby "share-link-title"
            :tab-index "-1"}
      [:div {:class (stl/css :share-link-header)}
       [:h2 {:id "share-link-title"
             :class (stl/css :share-link-title)}
        (tr "common.share-link.title")]
       [:> icon-button* {:variant "ghost"
                         :class (stl/css :share-link-close-button)
                         :aria-label (tr "labels.close")
                         :icon i/close
                         :on-click on-close}]]

      [:div {:class (stl/css :share-link-scroll)
             :hidden (some? link-to-destroy)}
       [:> share-link-content* {:file file
                                :page page
                                :on-request-destroy on-request-destroy}]]

      (when (some? link-to-destroy)
        [:> destroy-link-confirmation* {:on-cancel on-cancel-destroy
                                        :on-destroy on-destroy-link}])]]))

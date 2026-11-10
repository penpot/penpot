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
   [app.config :as cf]
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
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(log/set-level! :warn)

(defn- link-params
  [{:keys [pages who-comment who-inspect]}]
  {:pages pages
   :who-comment who-comment
   :who-inspect who-inspect})

(defn- permission-label
  [value]
  (if (= "all" value)
    (tr "common.share-link.all-users")
    (tr "common.share-link.team-members")))

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
  [{:keys [title children]}]
  (let [{:keys [expanded content-id toggle]} (use-collapsible)]
    [:div
     [:button {:class (stl/css :collapsible-title :collapsible-title-panel)
               :type "button"
               :aria-expanded expanded
               :aria-controls (when expanded content-id)
               :on-click toggle}
      [:> icon* {:icon-id (if expanded i/arrow-down i/arrow-right)
                 :class (stl/css :collapsible-title-icon)}]
      title]

     (when ^boolean expanded
       [:div {:id content-id}
        children])]))

(mf/defc collapsible-link*
  {::mf/private true
   ::mf/memo true}
  [{:keys [file link get-link-url]}]
  (let [{:keys [pages who-comment who-inspect]} link
        page-names (dc/get-link-page-names file pages)
        url        (get-link-url link)

        {:keys [expanded content-id toggle]} (use-collapsible)

        on-copy-to-clipboard
        (mf/use-fn
         (mf/deps url)
         (fn [_]
           (copy-url-to-clipboard url)))]

    [:div {:class (stl/css :collapsible-link)}
     [:div {:class (stl/css :collapsible-link-header)}
      [:button {:class (stl/css :collapsible-title :collapsible-title-link)
                :type "button"
                :aria-expanded expanded
                :aria-controls content-id
                :on-click toggle}
       [:> icon* {:icon-id (if expanded i/arrow-down i/arrow-right)
                  :class (stl/css :collapsible-title-icon)}]
       [:span {:class (stl/css :collapsible-link-url) :title url} url]]

      [:> icon-button* {:variant "ghost"
                        :aria-label (tr "viewer.header.share.copy-link")
                        :icon i/clipboard
                        :on-click on-copy-to-clipboard}]]

     (when ^boolean expanded
       [:div {:id content-id}
        [:div {:class (stl/css :collapsible-link-details)}
         [:span {:class (stl/css :collapsible-link-label)} (tr "common.share-link.permissions-pages")]
         [:span {:class (stl/css :collapsible-link-value)} (if (str/empty? page-names)
                                                             (tr "common.share-link.no-pages")
                                                             page-names)]

         [:span {:class (stl/css :collapsible-link-label)} (tr "common.share-link.permissions-can-comment")]
         [:span {:class (stl/css :collapsible-link-value)} (permission-label who-comment)]

         [:span {:class (stl/css :collapsible-link-label)} (tr "common.share-link.permissions-can-inspect")]
         [:span {:class (stl/css :collapsible-link-value)} (permission-label who-inspect)]]])]))

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
  (let [locale      (mf/deref i18n/locale)
        selected    (:pages link-options)
        permissions (mf/with-memo [locale]
                      [{:id "team" :label (tr "common.share-link.team-members")}
                       {:id "all"  :label (tr "common.share-link.all-users")}])]

    [:> collapsible-panel* {:class (stl/css :permissions-title)
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

(mf/defc links-section*
  {::mf/private true}
  [{:keys [file links get-link-url]}]
  [:> collapsible-panel* {:class (stl/css :links-title)
                          :title (dm/str (tr "common.share-link.links-title")
                                         " (" (count links) ")")}
   [:ul {:class (stl/css :links-content)}
    (for [{:keys [id] :as link} links]
      [:li {:key (dm/str id)}
       [:> collapsible-link* {:file file
                              :link link
                              :get-link-url get-link-url}]])]])

(mf/defc destroy-confirmation*
  {::mf/private true}
  [{:keys [on-cancel on-destroy]}]
  (let [cancel-ref (mf/use-ref nil)

        on-cancel-ref
        (mf/use-fn
         (fn [node]
           (mf/set-ref-val! cancel-ref node)))

        on-key-down
        (fn [event]
          (when (kbd/esc? event)
            (dom/stop-propagation event)
            (on-cancel event)))]

    (mf/with-effect []
      (some-> (mf/ref-val cancel-ref) dom/focus!))

    [:div {:class (stl/css :confirm)
           :role "alertdialog"
           :aria-modal true
           :aria-labelledby "share-link-title"
           :aria-describedby "share-link-confirm-description"
           :tab-index "-1"
           :on-key-down on-key-down}

     [:div {:id "share-link-confirm-description"
            :class (stl/css :confirm-description)}
      (tr "common.share-link.confirm-deletion-link-description")]

     [:div {:class (stl/css :confirm-actions)}
      [:> button* {:variant "secondary"
                   :type "button"
                   :on-ref on-cancel-ref
                   :on-click on-cancel}
       (tr "labels.cancel")]
      [:> button* {:variant "destructive"
                   :type "button"
                   :on-click on-destroy}
       (tr "common.share-link.destroy-link")]]]))

(mf/defc modal-content*
  {::mf/private true}
  [{:keys [file page on-request-destroy on-destroy-ref]}]
  (let [share-links      (mf/deref refs/share-links)
        router           (mf/deref refs/router)
        route            (mf/deref refs/route)
        zoom-type        (mf/deref refs/viewer-zoom-type)

        page-ids         (dm/get-in file [:data :pages])
        all-page-ids     (into #{} page-ids)

        show-links?      (and (contains? cf/flags :shared-links)
                              (seq share-links))

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

        get-link-url
        (mf/use-fn
         (mf/deps router route page-ids zoom-type)
         (fn [link]
           (link->url router route page-ids zoom-type link)))

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

    [:div {:class (stl/css :modal-content)}
     [:section {:class (stl/css :main-section)}
      (when (some? current-share-url)
        [:div {:class (stl/css :main-url)}
         [:> input-field* {:id "share-link-url-input"
                           :type "text"
                           :class (stl/css :main-url-input)
                           :value (or current-share-url "")
                           :placeholder (tr "common.share-link.placeholder")
                           :aria-label (tr "common.share-link.placeholder")
                           :read-only true}]
         [:> icon-button* {:variant "secondary"
                           :class (stl/css :main-url-button)
                           :aria-label (tr "viewer.header.share.copy-link")
                           :icon i/clipboard
                           :on-click on-copy-current-share-url}]])

      [:div {:class (stl/css :main-sub-row)}
       [:div {:class (stl/css :main-hint)} (tr "common.share-link.permissions-hint")]
       (if (some? current-share-link)
         [:> button* {:variant "destructive"
                      :type "button"
                      :on-ref on-destroy-ref
                      :on-click on-destroy-current-share-link}
          (tr "common.share-link.destroy-link")]
         [:> button* {:variant "primary"
                      :type "button"
                      :on-click on-create-link}
          (tr "common.share-link.get-link")])]]

     [:section {:class (stl/css :permissions-section)}
      [:> permissions-section* {:file file
                                :page page
                                :link-options link-options
                                :on-toggle-all on-toggle-all
                                :on-checked-page on-checked-page
                                :on-comment-change on-comment-change
                                :on-inspect-change on-inspect-change}]]

     (when show-links?
       [:section {:class (stl/css :links-section)}
        [:> links-section* {:file file
                            :links share-links
                            :get-link-url get-link-url}]])]))

(mf/defc modal
  {::mf/register modal/components
   ::mf/register-as :share-link
   ::mf/wrap-props false}
  [{:keys [file page]}]
  (let [link-to-destroy* (mf/use-state nil)
        link-to-destroy  (deref link-to-destroy*)

        dialog-ref       (mf/use-ref nil)
        destroy-btn-ref  (mf/use-ref nil)
        focus-target*    (mf/use-ref nil)

        on-close
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (modal/hide!)))

        on-request-destroy
        (mf/use-fn
         (fn [link]
           (reset! link-to-destroy* link)))

        on-destroy-ref
        (mf/use-fn
         (fn [node]
           (mf/set-ref-val! destroy-btn-ref node)))

        on-destroy-link
        (fn [_]
          (let [link link-to-destroy]
            (mf/set-ref-val! focus-target* :dialog)
            (reset! link-to-destroy* nil)
            (st/emit! (dc/delete-share-link link))))

        on-cancel-destroy
        (fn [_]
          (mf/set-ref-val! focus-target* :destroy-button)
          (reset! link-to-destroy* nil))]

    (mf/with-effect []
      (some-> (mf/ref-val dialog-ref) dom/focus!))

    (mf/with-effect [link-to-destroy]
      (when (nil? link-to-destroy)
        (when-let [target (mf/ref-val focus-target*)]
          (mf/set-ref-val! focus-target* nil)
          (case target
            :destroy-button
            (some-> (mf/ref-val destroy-btn-ref) dom/focus!)

            :dialog
            (some-> (mf/ref-val dialog-ref) dom/focus!)))))

    [:div {:class (stl/css :modal)}
     [:div {:ref dialog-ref
            :class (stl/css :modal-inner)
            :role "dialog"
            :aria-modal true
            :aria-labelledby "share-link-title"
            :tab-index "-1"}
      [:div {:class (stl/css :modal-header)}
       [:h2 {:id "share-link-title"
             :class (stl/css :modal-title)}
        (tr "common.share-link.title")]
       [:> icon-button* {:variant "ghost"
                         :class (stl/css :modal-close-button)
                         :aria-label (tr "labels.close")
                         :icon i/close
                         :on-click on-close}]]

      [:div {:class (stl/css :modal-scroll)
             :hidden (some? link-to-destroy)}
       [:> modal-content* {:file file
                           :page page
                           :on-request-destroy on-request-destroy
                           :on-destroy-ref on-destroy-ref}]]

      (when (some? link-to-destroy)
        [:> destroy-confirmation* {:on-cancel on-cancel-destroy
                                   :on-destroy on-destroy-link}])]]))

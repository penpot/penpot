;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.math :as mth]
   [app.common.spec :as us]
   [app.main.data.dashboard :as dd]
   [app.main.data.dashboard.shortcuts :as sc]
   [app.main.data.events :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.dashboard.export]
   [app.main.ui.dashboard.files :refer [files-section]]
   [app.main.ui.dashboard.fonts :refer [fonts-page font-providers-page]]
   [app.main.ui.dashboard.import]
   [app.main.ui.dashboard.libraries :refer [libraries-page]]
   [app.main.ui.dashboard.projects :refer [projects-section]]
   [app.main.ui.dashboard.search :refer [search-page]]
   [app.main.ui.dashboard.sidebar :refer [sidebar]]
   [app.main.ui.dashboard.team :refer [team-settings-page team-members-page team-invitations-page team-webhooks-page]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.router :as rt]
   [cuerdas.core :as str]
   [goog.events :as events]
   [okulary.core :as l]
   [potok.core :as ptk]
   [rumext.v2 :as mf])
  (:import goog.events.EventType))

(defn ^boolean uuid-str?
  [s]
  (and (string? s)
       (boolean (re-seq us/uuid-rx s))))

(defn- parse-params
  [route]
  (let [search-term (get-in route [:params :query :search-term])
        team-id     (get-in route [:params :path :team-id])
        project-id  (get-in route [:params :path :project-id])]
    (cond->
      {:search-term search-term}

      (uuid-str? team-id)
      (assoc :team-id (uuid team-id))

      (uuid-str? project-id)
      (assoc :project-id (uuid project-id)))))

(def builtin-templates
  (l/derived :builtin-templates st/state))

(mf/defc templates-section
  [{:keys [default-project-id profile project team content-width] :as props}]
  (let [templates   (->>  (mf/deref builtin-templates)
                          (filter #(not= (:id %) "tutorial-for-beginners")))

        route       (mf/deref refs/route)
        route-name  (get-in route [:data :name])
        section     (if (= route-name :dashboard-files)
                      (if (= (:id project) default-project-id)
                        "dashboard-drafts"
                        "dashboard-project")
                      (name route-name))
        props       (some-> profile (get :props {}))
        collapsed   (:builtin-templates-collapsed-status props false)
        card-offset (mf/use-state 0)

        card-width  275
        num-cards   (count templates)
        container-size (* (+ 2 num-cards) card-width)
        ;; We need space for num-cards plus the libraries&templates link
        more-cards  (> (+ @card-offset (* (+ 1 num-cards) card-width)) content-width)
        visible-card-count (mth/floor (/ content-width 275))
        left-moves (/ @card-offset -275)
        first-visible-card left-moves
        last-visible-card (+ (- visible-card-count 1) left-moves)
        content-ref (mf/use-ref)

        toggle-collapse
        (fn []
          (st/emit!
           (du/update-profile-props {:builtin-templates-collapsed-status (not collapsed)})))

        move-left
        (fn []
          (when-not (zero? @card-offset)
            (dom/animate! (mf/ref-val content-ref)
                          [#js {:left (str @card-offset "px")}
                           #js {:left (str (+ @card-offset card-width) "px")}]
                          #js {:duration 200
                               :easing "linear"})
            (reset! card-offset (+ @card-offset card-width))))

        move-right
        (fn []
          (when more-cards (swap! card-offset inc)
                (dom/animate! (mf/ref-val content-ref)
                              [#js {:left (str @card-offset "px")}
                               #js {:left (str (- @card-offset card-width) "px")}]
                              #js {:duration 200
                                   :easing "linear"})
                (reset! card-offset (- @card-offset card-width))))

        on-finish-import
        (fn [template]
          (st/emit!
           (ptk/event ::ev/event {::ev/name "import-template-finish"
                                  ::ev/origin "dashboard"
                                  :template (:name template)
                                  :section section})
           (when (not (some? project)) (rt/nav :dashboard-files
                                               {:team-id (:id team)
                                                :project-id default-project-id}))))

        import-template
        (fn [template]
          (let [templates-project-id (if project (:id project) default-project-id)]
            (st/emit!
             (ptk/event ::ev/event {::ev/name "import-template-launch"
                                    ::ev/origin "dashboard"
                                    :template (:name template)
                                    :section section})

             (modal/show
              {:type :import
               :project-id templates-project-id
               :files []
               :template template
               :on-finish-import (partial on-finish-import template)}))))

        handle-template-link
        (fn []
          (st/emit! (ptk/event ::ev/event {::ev/name "explore-libraries-click"
                                           ::ev/origin "dashboard"
                                           :section section})))]


    [:div.dashboard-templates-section {:class (when collapsed "collapsed")}
     [:div.title
      [:button {:tab-index "0"
                :on-click toggle-collapse
                :on-key-down (fn [event]
                               (when (kbd/enter? event)
                                 (dom/prevent-default event)
                                 (toggle-collapse))
                               )}
       [:span (tr "dashboard.libraries-and-templates")]
       [:span.icon (if collapsed i/arrow-up i/arrow-down)]]]
     [:div.content {:ref content-ref
                    :style {:left @card-offset :width (str container-size "px")}} 
      
      (for [num-item (range (count templates)) :let [item (nth templates num-item)]]
        (let [is-visible? (and (>= num-item first-visible-card) (<= num-item last-visible-card))]
          [:a.card-container {:tab-index (if (or (not is-visible?) collapsed)
                                           "-1"
                                           "0")
                              :id (str/concat "card-container-" num-item)
                              :key (:id item)
                              :on-click #(import-template item)
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (import-template item)))}
           [:div.template-card
            [:div.img-container
             [:img {:src (:thumbnail-uri item)
                    :alt (:name item)}]]
            [:div.card-name [:span (:name item)] [:span.icon i/download]]]]))

      (let [is-visible? (and (>= num-cards first-visible-card) (<= num-cards last-visible-card))]
        [:div.card-container
         [:div.template-card
          [:div.img-container
           [:a {:id (str/concat "card-container-" num-cards)
                :tab-index (if (or (not is-visible?) collapsed)
                             "-1"
                             "0")
                :href "https://penpot.app/libraries-templates.html"
                :target "_blank"
                :on-click handle-template-link
                :on-key-down (fn [event]
                               (when (kbd/enter? event)
                                 (handle-template-link)))}
            [:div.template-link
             [:div.template-link-title (tr "dashboard.libraries-and-templates")]
             [:div.template-link-text (tr "dashboard.libraries-and-templates.explore")]]]]]])]
       (when (< @card-offset 0)
         [:button.button.left {:tab-index (if collapsed
                                            "-1"
                                            "0")
                               :on-click move-left
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (move-left)
                                                (let [first-element (dom/get-element (str/concat "card-container-" first-visible-card))]
                                                  (when first-element
                                                    (dom/focus! first-element)))))} i/go-prev])
     (when more-cards
       [:button.button.right {:tab-index (if collapsed
                                           "-1"
                                           "0")
                              :on-click move-right
                              :aria-label (tr "labels.next")
                              :on-key-down  (fn [event]
                                             (when (kbd/enter? event)
                                               (move-right)
                                               (let [last-element (dom/get-element (str/concat "card-container-" last-visible-card))]
                                                 (when last-element
                                                   (dom/focus! last-element)))))} i/go-next])]))

(mf/defc dashboard-content
  [{:keys [team projects project section search-term profile] :as props}]
  (let [container          (mf/use-ref)
        content-width      (mf/use-state 0)
        default-project-id
        (->> (vals projects)
             (d/seek :is-default)
             (:id))
        on-resize
        (fn [_]
          (let [dom   (mf/ref-val container)
                width (obj/get dom "clientWidth")]
            (reset! content-width width)))]

    (mf/use-effect
     #(let [key1 (events/listen js/window "resize" on-resize)]
        (fn []
          (events/unlistenByKey key1))))

    (mf/use-effect on-resize)
    [:div.dashboard-content {:on-click #(st/emit! (dd/clear-selected-files)) :ref container}
     (case section
       :dashboard-projects
       [:*
        [:& projects-section {:team team
                              :projects projects
                              :profile profile
                              :default-project-id default-project-id}]
        [:& templates-section {:profile profile
                               :project project
                               :default-project-id default-project-id
                               :team team
                               :content-width @content-width}]]

       :dashboard-fonts
       [:& fonts-page {:team team}]

       :dashboard-font-providers
       [:& font-providers-page {:team team}]

       :dashboard-files
       (when project
         [:*
          [:& files-section {:team team :project project}]
          [:& templates-section {:profile profile
                                 :project project
                                 :default-project-id default-project-id
                                 :team team
                                 :content-width @content-width}]])

       :dashboard-search
       [:& search-page {:team team
                        :search-term search-term}]

       :dashboard-libraries
       [*
        [:& libraries-page {:team team}]]

       :dashboard-team-members
       [:& team-members-page {:team team :profile profile}]

       :dashboard-team-invitations
       [:& team-invitations-page {:team team}]

       :dashboard-team-webhooks
       [:& team-webhooks-page {:team team}]

       :dashboard-team-settings
       [:& team-settings-page {:team team :profile profile}]

       nil)]))

(mf/defc dashboard
  [{:keys [route profile] :as props}]
  (let [section      (get-in route [:data :name])
        params       (parse-params route)

        project-id   (:project-id params)
        team-id      (:team-id params)
        search-term  (:search-term params)

        teams        (mf/deref refs/teams)
        team         (get teams team-id)

        projects     (mf/deref refs/dashboard-projects)
        project      (get projects project-id)]

    (hooks/use-shortcuts ::dashboard sc/shortcuts)

    (mf/with-effect [team-id]
      (st/emit! (dd/initialize {:id team-id})))

    (mf/use-effect
     (fn []
       (dom/set-html-theme-color clr/white "light")
       (let [events [(events/listen goog/global EventType.KEYDOWN
                                    (fn [event]
                                      (when (kbd/enter? event)
                                        (st/emit! (dd/open-selected-file)))))]]
         (fn []
           (doseq [key events]
             (events/unlistenByKey key))))))

    [:& (mf/provider ctx/current-team-id) {:value team-id}
     [:& (mf/provider ctx/current-project-id) {:value project-id}
      ;; NOTE: dashboard events and other related functions assumes
      ;; that the team is a implicit context variable that is
      ;; available using react context or accessing
      ;; the :current-team-id on the state. We set the key to the
      ;; team-id because we want to completely refresh all the
      ;; components on team change. Many components assumes that the
      ;; team is already set so don't put the team into mf/deps.
      (when team
        [:main.dashboard-layout {:key (:id team)}
         [:& sidebar
          {:team team
           :projects projects
           :project project
           :profile profile
           :section section
           :search-term search-term}]
         (when (and team (seq projects))
           [:& dashboard-content
            {:projects projects
             :profile profile
             :project project
             :section section
             :search-term search-term
             :team team}])])]]))

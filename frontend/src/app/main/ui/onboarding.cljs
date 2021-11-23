;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.onboarding
  (:require
   [app.common.spec :as us]
   [app.config :as cf]
   [app.main.data.dashboard :as dd]
   [app.main.data.messages :as dm]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as i]
   [app.main.ui.releases.common :as rc]
   [app.main.ui.releases.v1-10]
   [app.main.ui.releases.v1-4]
   [app.main.ui.releases.v1-5]
   [app.main.ui.releases.v1-6]
   [app.main.ui.releases.v1-7]
   [app.main.ui.releases.v1-8]
   [app.main.ui.releases.v1-9]
   [app.util.dom :as dom]
   [app.util.http :as http]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.object :as obj]
   [app.util.router :as rt]
   [app.util.timers :as tm]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]))

;; --- ONBOARDING LIGHTBOX

(mf/defc onboarding-start
  [{:keys [next] :as props}]
  [:div.modal-container.onboarding
   [:div.modal-left.welcome
    [:img {:src "images/login-on.jpg" :border "0" :alt (tr "onboarding.welcome.alt")}]]
   [:div.modal-right
    [:div.modal-title
     [:h2 (tr "onboarding.welcome.title")]]
    [:span.release "Beta version " (:main @cf/version)]
    [:div.modal-content
     [:p (tr "onboarding.welcome.desc1")]
     [:p (tr "onboarding.welcome.desc2")]
     [:p (tr "onboarding.welcome.desc3")]]
    [:div.modal-navigation
     [:button.btn-secondary {:on-click next} (tr "labels.continue")]]]
   [:img.deco {:src "images/deco-left.png" :border "0"}]
   [:img.deco.right {:src "images/deco-right.png" :border "0"}]])

(mf/defc onboarding-opensource
  [{:keys [next] :as props}]
  [:div.modal-container.onboarding.black
   [:div.modal-left
    [:img {:src "images/open-source.svg" :border "0" :alt (tr "onboarding.contrib.alt")}]]
   [:div.modal-right
    [:div.modal-title
     [:h2 (tr "onboarding.contrib.title")]]
    [:div.modal-content
     [:p (tr "onboarding.contrib.desc1")]
     [:p
      (tr "onboarding.contrib.desc2.1")
      "\u00A0"
      [:a {:href "https://github.com/penpot" :target "_blank"} (tr "onboarding.contrib.link")]
      "\u00A0"
      (tr "onboarding.contrib.desc2.2")]]
    [:div.modal-navigation
     [:button.btn-secondary {:on-click next} (tr "labels.continue")]]]])

(defmulti render-slide :slide)

(defmethod render-slide 0
  [{:keys [navigate skip slide] :as props}]
  (mf/html
   [:div.modal-container.onboarding.feature
    [:div.modal-left
     [:img {:src "images/on-design.gif" :border "0" :alt (tr "onboarding.slide.0.alt")}]]
    [:div.modal-right
     [:div.modal-title
      [:h2 (tr "onboarding.slide.0.title")]]
     [:div.modal-content
      [:p (tr "onboarding.slide.0.desc1")]
      [:p (tr "onboarding.slide.0.desc2")]]
     [:div.modal-navigation
      [:button.btn-secondary {:on-click #(navigate 1)} (tr "labels.continue")]
      [:span.skip {:on-click skip} (tr "labels.skip")]
      [:& rc/navigation-bullets
       {:slide slide
        :navigate navigate
        :total 4}]]]]))

(defmethod render-slide 1
  [{:keys [navigate slide skip] :as props}]
  (mf/html
   [:div.modal-container.onboarding.feature
    [:div.modal-left
     [:img {:src "images/on-proto.gif" :border "0" :alt (tr "onboarding.slide.1.alt")}]]
    [:div.modal-right
     [:div.modal-title
      [:h2 (tr "onboarding.slide.1.title")]]
     [:div.modal-content
      [:p (tr "onboarding.slide.1.desc1")]
      [:p (tr "onboarding.slide.1.desc2")]]
     [:div.modal-navigation
      [:button.btn-secondary {:on-click #(navigate 2)} (tr "labels.continue")]
      [:span.skip {:on-click skip} (tr "labels.skip")]
      [:& rc/navigation-bullets
       {:slide slide
        :navigate navigate
        :total 4}]]]]))

(defmethod render-slide 2
  [{:keys [navigate slide skip] :as props}]
  (mf/html
   [:div.modal-container.onboarding.feature
    [:div.modal-left
     [:img {:src "images/on-feed.gif" :border "0" :alt (tr "onboarding.slide.2.alt")}]]
    [:div.modal-right
     [:div.modal-title
      [:h2 (tr "onboarding.slide.2.title")]]
     [:div.modal-content
      [:p (tr "onboarding.slide.2.desc1")]]
     [:div.modal-navigation
      [:button.btn-secondary {:on-click #(navigate 3)} (tr "labels.continue")]
      [:span.skip {:on-click skip} (tr "labels.skip")]
      [:& rc/navigation-bullets
       {:slide slide
        :navigate navigate
        :total 4}]]]]))

(defmethod render-slide 3
  [{:keys [navigate slide skip] :as props}]
  (mf/html
   [:div.modal-container.onboarding.feature
    [:div.modal-left
     [:img {:src "images/on-handoff.gif" :border "0" :alt (tr "onboarding.slide.3.alt")}]]
    [:div.modal-right
     [:div.modal-title
      [:h2 (tr "onboarding.slide.3.title")]]
     [:div.modal-content
      [:p (tr "onboarding.slide.3.desc1")]
      [:p (tr "onboarding.slide.3.desc2")]]
     [:div.modal-navigation
      [:button.btn-secondary {:on-click skip} (tr "labels.start")]
      [:& rc/navigation-bullets
       {:slide slide
        :navigate navigate
        :total 4}]]]]))

(mf/defc onboarding-modal
  {::mf/register modal/components
   ::mf/register-as :onboarding}
  [props]
  (let [slide (mf/use-state :start)
        klass (mf/use-state "fadeInDown")

        navigate
        (mf/use-callback #(reset! slide %))

        skip
        (mf/use-callback
         (st/emitf (modal/hide)
                   (modal/show {:type :onboarding-choice})
                   (du/mark-onboarding-as-viewed)))]

    (mf/use-layout-effect
     (mf/deps @slide)
     (fn []
       (when (not= :start @slide)
         (reset! klass "fadeIn"))
       (let [sem (tm/schedule 300 #(reset! klass nil))]
         (fn []
           (reset! klass nil)
           (tm/dispose! sem)))))

    [:div.modal-overlay
     [:div.animated {:class @klass}
      (case @slide
        :start      [:& onboarding-start {:next #(navigate :opensource)}]
        :opensource [:& onboarding-opensource {:next #(navigate 0)}]
        (render-slide
         (assoc props
                :slide @slide
                :navigate navigate
                :skip skip)))]]))

(s/def ::name ::us/not-empty-string)
(s/def ::team-form
  (s/keys :req-un [::name]))

(mf/defc onboarding-choice-modal
  {::mf/register modal/components
   ::mf/register-as :onboarding-choice}
  []
  (let [;; When user choices the option of `fly solo`, we proceed to show
        ;; the onboarding templates modal.
        on-fly-solo
        (fn []
          (tm/schedule 400  #(st/emit! (modal/show {:type :onboarding-templates}))))

        ;; When user choices the option of `team up`, we proceed to show
        ;; the team creation modal.
        on-team-up
        (fn []
          (st/emit! (modal/show {:type :onboarding-team})))
        ]

    [:div.modal-overlay
     [:div.modal-container.onboarding.final.animated.fadeInUp
      [:div.modal-top
       [:h1 (tr "onboarding.choice.title")]
       [:p (tr "onboarding.choice.desc")]]
      [:div.modal-columns
       [:div.modal-left
        [:div.content-button {:on-click on-fly-solo}
         [:h2 (tr "onboarding.choice.fly-solo")]
         [:p (tr "onboarding.choice.fly-solo-desc")]]]
       [:div.modal-right
        [:div.content-button {:on-click on-team-up}
         [:h2 (tr "onboarding.choice.team-up")]
         [:p (tr "onboarding.choice.team-up-desc")]]]]
      [:img.deco {:src "images/deco-left.png" :border "0"}]
      [:img.deco.right {:src "images/deco-right.png" :border "0"}]]]))

(mf/defc onboarding-team-modal
  {::mf/register modal/components
   ::mf/register-as :onboarding-team}
  []
  (let [form  (fm/use-form :spec ::team-form
                           :initial {})
        on-submit
        (mf/use-callback
         (fn [form _]
           (let [tname (get-in @form [:clean-data :name])]
             (st/emit! (modal/show {:type :onboarding-team-invitations :name tname})))))]

    [:div.modal-overlay
     [:div.modal-container.onboarding-team
      [:div.title
       [:h2 (tr "onboarding.choice.team-up")]
       [:p (tr "onboarding.choice.team-up-desc")]]

      [:& fm/form {:form form
                   :on-submit on-submit}

       [:div.team-row
        [:& fm/input {:type "text"
                      :name :name
                      :label (tr "onboarding.team-input-placeholder")}]]

       [:div.buttons
        [:button.btn-secondary.btn-large
         {:on-click #(st/emit! (modal/show {:type :onboarding-choice}))}
         (tr "labels.cancel")]
        [:& fm/submit-button
         {:label (tr "labels.next")}]]]

      [:img.deco {:src "images/deco-left.png" :border "0"}]
      [:img.deco.right {:src "images/deco-right.png" :border "0"}]]]))

(defn get-available-roles
  []
  [{:value "editor" :label (tr "labels.editor")}
   {:value "admin" :label (tr "labels.admin")}])

(s/def ::email ::us/email)
(s/def ::role  ::us/keyword)
(s/def ::invite-form
  (s/keys :req-un [::role ::email]))

;; This is the final step of team creation, consists in provide a
;; shortcut for invite users.

(mf/defc onboarding-team-invitations-modal
  {::mf/register modal/components
   ::mf/register-as :onboarding-team-invitations}
  [{:keys [name] :as props}]
  (let [initial (mf/use-memo (constantly
                              {:role "editor"
                               :name name}))
        form    (fm/use-form :spec ::invite-form
                             :initial initial)

        roles   (mf/use-memo #(get-available-roles))

        on-success
        (mf/use-callback
         (fn [_form response]
           (let [project-id (:default-project-id response)
                 team-id    (:id response)]
             (st/emit!
              (modal/hide)
              (rt/nav :dashboard-projects {:team-id team-id}))
             (tm/schedule 400 #(st/emit!
                                (modal/show {:type :onboarding-templates
                                             :project-id project-id}))))))

        on-error
        (mf/use-callback
         (fn [_form _response]
           (st/emit! (dm/error "Error on creating team."))))

        ;; The SKIP branch only creates the team, without invitations
        on-skip
        (mf/use-callback
         (fn [_]
           (let [mdata  {:on-success (partial on-success form)
                         :on-error   (partial on-error form)}
                 params {:name name}]
             (st/emit! (dd/create-team (with-meta params mdata))))))

        ;; The SUBMIT branch creates the team with the invitations
        on-submit
        (mf/use-callback
         (fn [form _]
           (let [mdata  {:on-success (partial on-success form)
                         :on-error   (partial on-error form)}
                 params (:clean-data @form)]
             (st/emit! (dd/create-team-with-invitations (with-meta params mdata))))))]

    [:div.modal-overlay
     [:div.modal-container.onboarding-team
      [:div.title
       [:h2 (tr "onboarding.choice.team-up")]
       [:p (tr "onboarding.choice.team-up-desc")]]

      [:& fm/form {:form form
                   :on-submit on-submit}

      [:div.invite-row
       [:& fm/input {:name :email
                     :label (tr "labels.email")}]
       [:& fm/select {:name :role
                      :options roles}]]

       [:div.buttons
        [:button.btn-secondary.btn-large
         {:on-click #(st/emit! (modal/show {:type :onboarding-choice}))}
         (tr "labels.cancel")]
        [:& fm/submit-button
         {:label (tr "labels.create")}]]
       [:div.skip-action
        {:on-click on-skip}
        [:div.action "Skip and invite later"]]]
      [:img.deco {:src "images/deco-left.png" :border "0"}]
      [:img.deco.right {:src "images/deco-right.png" :border "0"}]]]))

(mf/defc template-item
  [{:keys [name path image project-id]}]
  (let [downloading? (mf/use-state false)
        link         (str (assoc cf/public-uri :path path))

        on-finish-import
        (fn []
          (st/emit! (dd/fetch-files {:project-id project-id})
                    (dd/fetch-recent-files)
                    (dd/clear-selected-files)))

        open-import-modal
        (fn [file]
          (st/emit! (modal/show
                     {:type :import
                      :project-id project-id
                      :files [file]
                      :on-finish-import on-finish-import})))
        on-click
        (fn []
          (reset! downloading? true)
          (->> (http/send! {:method :get :uri link :response-type :blob :mode :no-cors})
               (rx/subs (fn [{:keys [body] :as response}]
                          (open-import-modal {:name name :uri (dom/create-uri body)}))
                        (fn [error]
                          (js/console.log "error" error))
                        (fn []
                          (reset! downloading? false)))))
        ]

    [:div.template-item
     [:div.template-item-content
      [:img {:src image}]]
     [:div.template-item-title
      [:div.label name]
      (if @downloading?
        [:div.action "Fetching..."]
        [:div.action {:on-click on-click} "+ Add to drafts"])]]))

(mf/defc onboarding-templates-modal
  {::mf/register modal/components
   ::mf/register-as :onboarding-templates}
  ;; NOTE: the project usually comes empty, it only comes fulfilled
  ;; when a user creates a new team just after signup.
  [{:keys [project-id] :as props}]
  (let [close-fn   (mf/use-callback #(st/emit! (modal/hide)))
        profile    (mf/deref refs/profile)
        project-id (or project-id (:default-project-id profile))]
    [:div.modal-overlay
     [:div.modal-container.onboarding-templates
      [:div.modal-header
       [:div.modal-close-button
        {:on-click close-fn} i/close]]

      [:div.modal-content
       [:h3 (tr "onboarding.templates.title")]
       [:p (tr "onboarding.templates.subtitle")]

       [:div.templates
        [:& template-item
         {:path "/github/penpot-files/Penpot-Design-system.penpot"
          :image "https://penpot.app/images/libraries/cover-ds-penpot.jpg"
          :name "Penpot Design System"
          :project-id project-id}]
        [:& template-item
         {:path "/github/penpot-files/Material-Design-Kit.penpot"
          :image "https://penpot.app/images/libraries/cover-material.jpg"
          :name "Material Design Kit"
          :project-id project-id}]]]]]))


;;; --- RELEASE NOTES MODAL

(mf/defc release-notes
  [{:keys [version] :as props}]
  (let [slide (mf/use-state :start)
        klass (mf/use-state "fadeInDown")

        navigate
        (mf/use-callback #(reset! slide %))

        next
        (mf/use-callback
         (mf/deps slide)
         (fn []
           (if (= @slide :start)
             (navigate 0)
             (navigate (inc @slide)))))

        finish
        (mf/use-callback
         (st/emitf (modal/hide)
                   (du/mark-onboarding-as-viewed {:version version})))
        ]

    (mf/use-effect
     (mf/deps)
     (fn []
       (st/emitf (du/mark-onboarding-as-viewed {:version version}))))

    (mf/use-layout-effect
     (mf/deps @slide)
     (fn []
       (when (not= :start @slide)
         (reset! klass "fadeIn"))
       (let [sem (tm/schedule 300 #(reset! klass nil))]
         (fn []
           (reset! klass nil)
           (tm/dispose! sem)))))

    (rc/render-release-notes
     {:next next
      :navigate navigate
      :finish finish
      :klass klass
      :slide slide
      :version version})))

(mf/defc release-notes-modal
  {::mf/wrap-props false
   ::mf/register modal/components
   ::mf/register-as :release-notes}
  [props]
  (let [versions (methods rc/render-release-notes)
        version  (obj/get props "version")]
    (when (contains? versions version)
      [:div.relnotes
       [:> release-notes props]])))

(defmethod rc/render-release-notes "0.0"
  [params]
  (rc/render-release-notes (assoc params :version "1.10")))

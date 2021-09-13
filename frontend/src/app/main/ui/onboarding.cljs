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
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.releases.common :as rc]
   [app.main.ui.releases.v1-4]
   [app.main.ui.releases.v1-5]
   [app.main.ui.releases.v1-6]
   [app.main.ui.releases.v1-7]
   [app.main.ui.releases.v1-8]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.object :as obj]
   [app.util.router :as rt]
   [app.util.timers :as tm]
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
    [:span.release "Alpha version " (:main @cf/version)]
    [:div.modal-content
     [:p (tr "onboarding.welcome.desc1")]
     [:p (tr "onboarding.welcome.desc2")]]
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
                   (modal/show {:type :onboarding-team})
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

(mf/defc onboarding-team-modal
  {::mf/register modal/components
   ::mf/register-as :onboarding-team}
  []
  (let [close (mf/use-fn (st/emitf (modal/hide)))
        form  (fm/use-form :spec ::team-form
                           :initial {})
        on-success
        (mf/use-callback
         (fn [_form response]
           (st/emit! (modal/hide)
                     (rt/nav :dashboard-projects {:team-id (:id response)}))))

        on-error
        (mf/use-callback
         (fn [_form _response]
           (st/emit! (dm/error "Error on creating team."))))

        on-submit
        (mf/use-callback
         (fn [form _event]
           (let [mdata  {:on-success (partial on-success form)
                         :on-error   (partial on-error form)}
                 params {:name (get-in @form [:clean-data :name])}]
             (st/emit! (dd/create-team (with-meta params mdata))))))]

    [:div.modal-overlay
     [:div.modal-container.onboarding.final.animated.fadeInUp
      [:div.modal-left
       [:img {:src "images/onboarding-team.jpg" :border "0" :alt (tr "onboarding.team.create.title")}]
       [:h2 (tr "onboarding.team.create.title")]
       [:p (tr "onboarding.team.create.desc1")]

       [:& fm/form {:form form
                    :on-submit on-submit}
        [:& fm/input {:type "text"
                   :name :name
                   :label (tr "onboarding.team.create.input-placeholder")}]
        [:& fm/submit-button
         {:label (tr "onboarding.team.create.button")}]]]

      [:div.modal-right
       [:img {:src "images/onboarding-start.jpg" :border "0" :alt (tr "onboarding.team.start.title")}]
       [:h2 (tr "onboarding.team.start.title")]
       [:p (tr "onboarding.team.start.desc1")]
       [:button.btn-primary.btn-large {:on-click close} (tr "onboarding.team.start.button")]]


      [:img.deco {:src "images/deco-left.png" :border "0"}]
      [:img.deco.right {:src "images/deco-right.png" :border "0"}]]]))


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
  (rc/render-release-notes (assoc params :version "1.8")))

